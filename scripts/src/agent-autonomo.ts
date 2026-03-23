#!/usr/bin/env tsx
/**
 * Agente Autónomo ReAct
 *
 * Arquitectura ReAct (Reasoning + Acting):
 * 1. Observation: Captura el estado actual de la pantalla del dispositivo
 * 2. Thought: El LLM razona sobre el estado y la tarea
 * 3. Action: El LLM decide qué acción ejecutar
 * 4. Loop: Repite hasta completar la tarea o alcanzar límite de intentos
 *
 * Agente: Groq LLM (groq-sdk)
 * Dispositivo: Android con servicio de accesibilidad en http://localhost:8080
 */

import Groq from "groq-sdk";

// ============================================================================
// TIPOS
// ============================================================================

interface UINode {
  text?: string;
  desc?: string;
  id?: string;
  cls: string;
  rect: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  clickable?: boolean;
  scrollable?: boolean;
  editable?: boolean;
}

interface ScreenState {
  package: string;
  nodes: UINode[];
  timestamp: number;
}

type ActionType = "CLICK" | "TYPE" | "SWIPE" | "WAIT" | "FINISH" | "HOME" | "BACK" | "RECENTS";

interface Action {
  type: ActionType;
  params?: {
    x?: number;
    y?: number;
    text?: string;
    direction?: "up" | "down" | "left" | "right";
    ms?: number;
    message?: string;
  };
}

interface StepRecord {
  step: number;
  observation: string;
  thought: string;
  action: Action;
  result: string;
  timestamp: number;
}

interface AgentConfig {
  bridgeUrl: string;
  maxSteps: number;
  taskDescription: string;
  verbose: boolean;
}

// ============================================================================
// CLIENTE HTTP
// ============================================================================

class AccessibilityBridgeClient {
  private baseUrl: string;

  constructor(bridgeUrl: string) {
    this.baseUrl = bridgeUrl.replace(/\/$/, "");
  }

  async getScreen(): Promise<ScreenState> {
    try {
      const response = await fetch(`${this.baseUrl}/screen`, {
        method: "GET",
        headers: { "Content-Type": "application/json" },
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const data = (await response.json()) as ScreenState;
      data.timestamp = Date.now();
      return data;
    } catch (error) {
      throw new Error(
        `Failed to get screen: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  }

  async performAction(action: Action): Promise<boolean> {
    try {
      const payload = this.serializeAction(action);

      const response = await fetch(`${this.baseUrl}/action`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const result = (await response.json()) as { success?: boolean };
      return result.success !== false;
    } catch (error) {
      throw new Error(
        `Failed to perform action: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  }

  private serializeAction(action: Action): Record<string, unknown> {
    switch (action.type) {
      case "CLICK":
        return {
          action: "click",
          x: action.params?.x ?? 0,
          y: action.params?.y ?? 0,
        };
      case "SWIPE":
        const dir = action.params?.direction ?? "down";
        const swipes: Record<string, { x: number; y: number; dx: number; dy: number }> = {
          up: { x: 540, y: 1200, dx: 0, dy: -500 },
          down: { x: 540, y: 300, dx: 0, dy: 500 },
          left: { x: 900, y: 600, dx: -400, dy: 0 },
          right: { x: 200, y: 600, dx: 400, dy: 0 },
        };
        const swipe = swipes[dir];
        return {
          action: "scroll",
          x: swipe.x,
          y: swipe.y,
          dx: swipe.dx,
          dy: swipe.dy,
        };
      case "TYPE":
        return {
          action: "input",
          text: action.params?.text ?? "",
        };
      case "WAIT":
        // WAIT se maneja localmente
        return { action: "wait", ms: action.params?.ms ?? 1000 };
      case "FINISH":
        // FINISH es terminal
        return { action: "finish", message: action.params?.message ?? "" };
      case "HOME":
      case "BACK":
      case "RECENTS":
        return {
          action: "global",
          type: action.type.toLowerCase(),
        };
      default:
        throw new Error(`Unknown action type: ${action.type}`);
    }
  }
}

// ============================================================================
// FORMATEADOR DE CONTEXTO
// ============================================================================

class ContextFormatter {
  static formatScreenForPrompt(screen: ScreenState): string {
    let output = `Current Screen State:\n`;
    output += `Package: ${screen.package}\n`;
    output += `Timestamp: ${new Date(screen.timestamp).toISOString()}\n\n`;

    if (screen.nodes.length === 0) {
      output += `No interactive elements detected on screen.\n`;
      return output;
    }

    output += `Interactive Elements (${screen.nodes.length} total):\n`;
    screen.nodes.forEach((node, idx) => {
      output += `\n[${idx}] ${node.cls}\n`;
      if (node.text) output += `    Text: "${node.text}"\n`;
      if (node.desc) output += `    Description: "${node.desc}"\n`;
      if (node.id) output += `    ID: ${node.id}\n`;
      output += `    Position (Top-Left): (${node.rect.x}, ${node.rect.y}) Size: ${node.rect.w}x${node.rect.h}\n`;
      const attrs = [];
      if (node.clickable) attrs.push("clickable");
      if (node.scrollable) attrs.push("scrollable");
      if (node.editable) attrs.push("editable");
      if (attrs.length > 0) output += `    Attributes: ${attrs.join(", ")}\n`;
    });

    return output;
  }

  static formatHistory(history: StepRecord[]): string {
    if (history.length === 0) return "No previous steps.";

    let output = `Step History (${history.length} steps):\n\n`;
    history.forEach((step) => {
      output += `Step ${step.step}:\n`;
      output += `  Thought: ${step.thought}\n`;
      output += `  Action: ${step.action.type}`;
      if (step.action.params) {
        output += ` (${JSON.stringify(step.action.params)})`;
      }
      output += `\n`;
      output += `  Result: ${step.result}\n\n`;
    });

    return output;
  }
}

// ============================================================================
// PARSEADOR DE ACCIONES
// ============================================================================

class ActionParser {
  static parse(llmOutput: string): Action | null {
    // Remove <think> blocks before parsing action
    const contentWithoutThink = llmOutput.replace(/<think>[\s\S]*?<\/think>/gi, "");

    // Busca patrones: ACTION: TYPE(params)
    const patterns = [
      /Action:\s*CLICK\(\s*(\d+)\s*,\s*(\d+)\s*\)/i,
      /Action:\s*TYPE\(\s*"([^"]*)"\s*\)/i,
      /Action:\s*SWIPE\(\s*"(up|down|left|right)"\s*\)/i,
      /Action:\s*WAIT\(\s*(\d+)\s*\)/i,
      /Action:\s*FINISH\(\s*"([^"]*)"\s*\)/i,
      /Action:\s*(HOME|BACK|RECENTS)\s*\(\s*\)/i,
      // Fallback: search without "Action:" prefix
      /CLICK\(\s*(\d+)\s*,\s*(\d+)\s*\)/i,
      /TYPE\(\s*"([^"]*)"\s*\)/i,
      /SWIPE\(\s*"(up|down|left|right)"\s*\)/i,
      /WAIT\(\s*(\d+)\s*\)/i,
      /FINISH\(\s*"([^"]*)"\s*\)/i,
      /(HOME|BACK|RECENTS)\s*\(\s*\)/i,
    ];

    const clickMatch = patterns[0].exec(contentWithoutThink) || patterns[6].exec(contentWithoutThink);
    if (clickMatch) {
      return {
        type: "CLICK",
        params: {
          x: parseInt(clickMatch[1], 10),
          y: parseInt(clickMatch[2], 10),
        },
      };
    }

    const typeMatch = patterns[1].exec(contentWithoutThink) || patterns[7].exec(contentWithoutThink);
    if (typeMatch) {
      return {
        type: "TYPE",
        params: { text: typeMatch[1] },
      };
    }

    const swipeMatch = patterns[2].exec(contentWithoutThink) || patterns[8].exec(contentWithoutThink);
    if (swipeMatch) {
      return {
        type: "SWIPE",
        params: { direction: swipeMatch[1].toLowerCase() as "up" | "down" | "left" | "right" },
      };
    }

    const waitMatch = patterns[3].exec(contentWithoutThink) || patterns[9].exec(contentWithoutThink);
    if (waitMatch) {
      return {
        type: "WAIT",
        params: { ms: parseInt(waitMatch[1], 10) },
      };
    }

    const finishMatch = patterns[4].exec(contentWithoutThink) || patterns[10].exec(contentWithoutThink);
    if (finishMatch) {
      return {
        type: "FINISH",
        params: { message: finishMatch[1] },
      };
    }

    const systemMatch = patterns[5].exec(contentWithoutThink) || patterns[11].exec(contentWithoutThink);
    if (systemMatch) {
      return {
        type: systemMatch[1].toUpperCase() as "HOME" | "BACK" | "RECENTS",
      };
    }

    return null;
  }
}

// ============================================================================
// AGENTE REACT
// ============================================================================

class ReActAgent {
  private groq: Groq;
  private client: AccessibilityBridgeClient;
  private config: AgentConfig;
  private history: StepRecord[] = [];
  private stepCount = 0;

  constructor(config: AgentConfig) {
    const apiKey = process.env.GROQ_API_KEY;
    if (!apiKey) {
      throw new Error("GROQ_API_KEY environment variable is required");
    }

    this.groq = new Groq({ apiKey });
    this.client = new AccessibilityBridgeClient(config.bridgeUrl);
    this.config = config;
  }

  private log(message: string, level: "INFO" | "DEBUG" | "WARN" | "ERROR" = "INFO") {
    const timestamp = new Date().toISOString();
    const prefix = `[${timestamp}] [${level}]`;
    if (this.config.verbose || level !== "DEBUG") {
      console.log(`${prefix} ${message}`);
    }
  }

  async run(): Promise<void> {
    this.log(`Starting ReAct agent: "${this.config.taskDescription}"`);
    this.log(`Bridge URL: ${this.config.bridgeUrl}`);
    this.log(`Max steps: ${this.config.maxSteps}`);

    try {
      // Initial observation
      let screen = await this.client.getScreen();
      this.log(`Initial screen loaded: ${screen.package}`, "DEBUG");

      while (this.stepCount < this.config.maxSteps) {
        this.stepCount++;
        this.log(`\n${"=".repeat(70)}`);
        this.log(`Step ${this.stepCount}/${this.config.maxSteps}`);
        this.log(`${"=".repeat(70)}`);

        // OBSERVATION
        const observationText = ContextFormatter.formatScreenForPrompt(screen);
        this.log(`Observation:\n${observationText}`, "DEBUG");

        // Prepare prompt
        const systemPrompt = this.buildSystemPrompt();
        const userPrompt = this.buildUserPrompt(observationText);

        // THOUGHT & ACTION (LLM)
        this.log(`Querying Groq LLM...`);
        const llmResponse = await this.queryLLM(systemPrompt, userPrompt);
        this.log(`LLM Response:\n${llmResponse}`, "DEBUG");

        // Parse action
        const action = ActionParser.parse(llmResponse);
        if (!action) {
          this.log(`Failed to parse action from LLM response`, "WARN");
          continue;
        }

        this.log(`Action: ${action.type}${action.params ? ` (${JSON.stringify(action.params)})` : ""}`);

        // Check for terminal action
        if (action.type === "FINISH") {
          this.log(`\n✓ TASK COMPLETED: ${action.params?.message || "No message"}`);
          const step: StepRecord = {
            step: this.stepCount,
            observation: "TASK_COMPLETED",
            thought: "Task has been completed",
            action,
            result: "success",
            timestamp: Date.now(),
          };
          this.history.push(step);
          break;
        }

        // Execute action
        let actionResult = "failed";
        try {
          if (action.type === "WAIT") {
            const ms = action.params?.ms || 1000;
            this.log(`Waiting ${ms}ms...`);
            await new Promise((resolve) => setTimeout(resolve, ms));
            actionResult = "success";
          } else {
            const success = await this.client.performAction(action);
            actionResult = success ? "success" : "failed";
            if (!success) {
              this.log(`Action execution returned failure`, "WARN");
            }
          }
        } catch (error) {
          actionResult = `error: ${error instanceof Error ? error.message : String(error)}`;
          this.log(`Action execution error: ${actionResult}`, "WARN");
        }

        // Re-observe after action
        try {
          screen = await this.client.getScreen();
          this.log(`Screen refreshed`, "DEBUG");
        } catch (error) {
          this.log(`Failed to refresh screen: ${error instanceof Error ? error.message : String(error)}`, "WARN");
        }

        // Record step
        const step: StepRecord = {
          step: this.stepCount,
          observation: observationText.substring(0, 200),
          thought: this.extractThought(llmResponse),
          action,
          result: actionResult,
          timestamp: Date.now(),
        };
        this.history.push(step);

        // Simple loop detection: check if we're repeating the same action
        if (this.history.length >= 2) {
          const lastAction = this.history[this.history.length - 1].action;
          const secondLastAction = this.history[this.history.length - 2].action;
          if (
            JSON.stringify(lastAction) === JSON.stringify(secondLastAction) &&
            lastAction.type !== "WAIT"
          ) {
            this.log(
              `Potential infinite loop detected (same action repeated). Waiting before retry.`,
              "WARN"
            );
            await new Promise((resolve) => setTimeout(resolve, 1000));
          }
        }
      }

      if (this.stepCount >= this.config.maxSteps) {
        this.log(`⚠ Max steps (${this.config.maxSteps}) reached`, "WARN");
      }

      this.log(`\n${"=".repeat(70)}`);
      this.log(`Agent execution finished. Total steps: ${this.stepCount}`);
      this.printSummary();
    } catch (error) {
      this.log(
        `Fatal error: ${error instanceof Error ? error.message : String(error)}`,
        "ERROR"
      );
      throw error;
    }
  }

  private buildSystemPrompt(): string {
    return `You are an autonomous agent controlling an Android device via accessibility API.

Your task: ${this.config.taskDescription}

You have access to the following actions:
- CLICK(x, y): Click at screen coordinates (x, y)
- TYPE("text"): Type text into the focused input field
- SWIPE("direction"): Perform a swipe gesture. Direction can be: up, down, left, right
- WAIT(ms): Wait for specified milliseconds
- FINISH("message"): Complete the task with a completion message
- HOME(): Press the system home button
- BACK(): Press the system back button
- RECENTS(): Open the system recent apps screen

IMPORTANT INSTRUCTIONS:
1. Always reason step-by-step before executing an action (Thought -> Action)
2. To go to the home screen or go back, use the system actions HOME() and BACK() instead of clicking on UI buttons labeled "HOME" or "BACK".
3. Analyze the current screen state carefully before deciding your next move
4. Look for interactive elements (buttons, inputs, clickable text) with their positions
5. If you need to scroll to find an element, use SWIPE actions
6. After performing an action, wait briefly for the UI to update
7. If the same action fails twice in a row, try a different approach
8. Avoid infinite loops by varying your actions
9. When the task is complete, use FINISH("message describing what was accomplished")

Format your response as:
<think>
your internal reasoning about current state and next step
</think>
Action: ACTION_TYPE(params)

You MUST always provide an Action line outside of any tags. Be concise and precise.`;
  }

  private buildUserPrompt(observation: string): string {
    let prompt = `${observation}\n\n`;

    if (this.history.length > 0) {
      prompt += `Recent action history:\n`;
      const recentSteps = this.history.slice(-3);
      recentSteps.forEach((step) => {
        prompt += `- Step ${step.step}: ${step.action.type}`;
        if (step.action.params) {
          prompt += ` -> ${step.result}`;
        }
        prompt += `\n`;
      });
      prompt += `\n`;
    }

    prompt += `What is your next action?`;

    return prompt;
  }

  private async queryLLM(systemPrompt: string, userPrompt: string): Promise<string> {
    const response = await this.groq.chat.completions.create({
      model: "qwen/qwen3-32b",
      messages: [
        {
          role: "system",
          content: systemPrompt,
        },
        {
          role: "user",
          content: userPrompt,
        },
      ],
      temperature: 0.3, // Lower temp for more deterministic behavior
      max_tokens: 1024,
    });

    const content = response.choices[0]?.message?.content;
    if (!content) {
      throw new Error("Empty response from LLM");
    }

    return content;
  }

  private extractThought(llmOutput: string): string {
    // Try to extract from <think> tags first
    const thinkMatch = /<think>([\s\S]*?)<\/think>/i.exec(llmOutput);
    if (thinkMatch) return thinkMatch[1].trim();

    // Fallback to Thought: prefix
    const thoughtMatch = /Thought:\s*(.+?)(?:\n|$)/i.exec(llmOutput);
    if (thoughtMatch) return thoughtMatch[1].trim();

    return "Could not extract thought";
  }

  private printSummary(): void {
    console.log(`\nExecution Summary:`);
    console.log(`Total Steps: ${this.history.length}`);

    if (this.history.length > 0) {
      const successCount = this.history.filter((s) => s.result === "success").length;
      const errorCount = this.history.filter((s) => s.result.includes("error")).length;
      console.log(`Successful Actions: ${successCount}`);
      console.log(`Failed/Error Actions: ${errorCount}`);

      const taskCompleted = this.history.some((s) => s.action.type === "FINISH");
      console.log(`Task Completed: ${taskCompleted ? "Yes ✓" : "No ✗"}`);
    }
  }
}

// ============================================================================
// MAIN
// ============================================================================

async function main() {
  // Read task from command line or use default
  const task =
    process.argv[2] ||
    "Open the Settings app and enable WiFi. Then take a screenshot to confirm WiFi is on.";

  const bridgeUrl = process.env.BRIDGE_URL || "http://localhost:8080";
  const maxSteps = parseInt(process.env.MAX_STEPS || "15", 10);
  const verbose = process.env.VERBOSE === "true";

  const config: AgentConfig = {
    bridgeUrl,
    taskDescription: task,
    maxSteps,
    verbose,
  };

  const agent = new ReActAgent(config);
  await agent.run();
}

main().catch((error) => {
  console.error(`Fatal error:`, error);
  process.exit(1);
});
