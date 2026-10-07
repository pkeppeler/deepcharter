import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

type JsonRecord = Record<string, string>;

export interface McpTestSession {
  client: Client;
  transport: StdioClientTransport;
  close: () => Promise<void>;
}

function getServerCommand(): { command: string; args: string[] } {
  const projectRoot = process.cwd();
  const useDistServer = process.env.MCP_E2E_USE_DIST === '1';
  const distEntry = join(projectRoot, 'dist', 'index.js');
  if (useDistServer && existsSync(distEntry)) {
    return {
      command: process.execPath,
      args: [distEntry],
    };
  }

  // Fallback for environments running tests without a prior TypeScript build.
  const tsxCli = join(projectRoot, 'node_modules', 'tsx', 'dist', 'cli.mjs');
  const srcEntry = join(projectRoot, 'src', 'index.ts');

  return {
    command: process.execPath,
    args: [tsxCli, srcEntry],
  };
}

function getInheritedEnv(): JsonRecord {
  const env: JsonRecord = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (typeof value === 'string') {
      env[key] = value;
    }
  }
  return env;
}

export async function createMcpSession(clientName: string): Promise<McpTestSession> {
  const { command, args } = getServerCommand();
  const env = getInheritedEnv();
  env.LOG_LEVEL = 'ERROR';

  const transport = new StdioClientTransport({
    command,
    args,
    cwd: process.cwd(),
    env,
    stderr: 'pipe',
  });

  const stderrChunks: string[] = [];
  const stderrStream = transport.stderr;
  if (stderrStream) {
    stderrStream.on('data', (chunk) => {
      stderrChunks.push(chunk.toString());
    });
  }

  const client = new Client({
    name: clientName,
    version: '1.0.0',
  });

  try {
    await client.connect(transport);
  } catch (error) {
    const stderrText = stderrChunks.join('').trim();
    const message =
      error instanceof Error ? error.message : `Unknown error: ${JSON.stringify(error)}`;

    throw new Error(
      stderrText
        ? `Failed to connect to MCP server: ${message}\nServer stderr:\n${stderrText}`
        : `Failed to connect to MCP server: ${message}`,
    );
  }

  return {
    client,
    transport,
    close: async () => {
      await client.close();
    },
  };
}

export function extractFirstText(content: unknown): string {
  if (!Array.isArray(content) || content.length === 0) {
    throw new Error('Expected tool result content array with at least one entry');
  }

  for (const item of content) {
    if (
      item &&
      typeof item === 'object' &&
      'type' in item &&
      (item as { type?: unknown }).type === 'text' &&
      'text' in item &&
      typeof (item as { text?: unknown }).text === 'string'
    ) {
      return (item as { text: string }).text;
    }
  }

  throw new Error('Expected at least one text content entry in tool result');
}
