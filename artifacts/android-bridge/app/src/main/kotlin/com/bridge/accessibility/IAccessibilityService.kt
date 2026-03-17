package com.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

class IAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: BridgeHttpServer? = null

    companion object {
        private const val TAG = "IAccessibilityService"
        var instance: IAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        httpServer = BridgeHttpServer(8080, this)
        httpServer?.start()
        Log.i(TAG, "HTTP server started on port 8080")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        httpServer?.stop()
        scope.cancel()
        Log.i(TAG, "Accessibility service destroyed")
    }

    fun getScreenJson(): JSONObject {
        val root = rootInActiveWindow
        val result = JSONObject()
        if (root == null) {
            result.put("error", "No active window")
            return result
        }
        result.put("package", root.packageName ?: "")
        result.put("nodes", serializeNode(root))
        root.recycle()
        return result
    }

    private fun serializeNode(node: AccessibilityNodeInfo?): JSONArray {
        val array = JSONArray()
        if (node == null) return array
        collectNodes(node, array)
        return array
    }

    private fun collectNodes(node: AccessibilityNodeInfo, output: JSONArray) {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        val isEditable = node.isEditable

        val hasContent = text.isNotEmpty() || desc.isNotEmpty()
        val isImportantContainer = isClickable || isScrollable || isEditable ||
                className.contains("RecyclerView") ||
                className.contains("ListView") ||
                className.contains("ScrollView") ||
                className.contains("ViewGroup") && resId.isNotEmpty()

        if (hasContent || isImportantContainer) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val cx = (bounds.left + bounds.right) / 2
            val cy = (bounds.top + bounds.bottom) / 2

            if (cx > 0 || cy > 0 || bounds.width() > 0) {
                val obj = JSONObject()
                if (text.isNotEmpty()) obj.put("text", text)
                if (desc.isNotEmpty()) obj.put("desc", desc)
                if (resId.isNotEmpty()) obj.put("id", resId)
                obj.put("cls", simplifyClassName(className))
                obj.put("rect", JSONObject().apply {
                    put("x", cx)
                    put("y", cy)
                    put("w", bounds.width())
                    put("h", bounds.height())
                })
                if (isClickable) obj.put("clickable", true)
                if (isScrollable) obj.put("scrollable", true)
                if (isEditable) obj.put("editable", true)
                output.put(obj)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, output)
            child.recycle()
        }
    }

    private fun simplifyClassName(cls: String): String {
        return cls.substringAfterLast('.')
    }

    fun performClick(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }

    fun performScroll(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x + dx, y + dy)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }

    fun performGlobalAction(type: String): Boolean {
        return when (type.uppercase()) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else false
            else -> false
        }
    }

    fun performInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditText(root)
        root.recycle()
        focused ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return result
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditText(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
}
