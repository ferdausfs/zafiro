/**
 * Server-side tools available to the brain without the phone:
 *   web_search — Tavily API when TAVILY_API_KEY is set, DuckDuckGo lite otherwise
 *   web_fetch  — fetch a URL and strip it down to readable text
 */

const FETCH_TEXT_LIMIT = 8000;
const SEARCH_RESULTS_LIMIT = 6;

export const SERVER_TOOLS: Record<string, { description: string; parameters: Record<string, unknown> }> = {
  web_search: {
    description:
      "Search the public web. Returns a list of {title, url, snippet}. Use for current events, prices, news, anything beyond your knowledge.",
    parameters: {
      type: "object",
      properties: {
        query: { type: "string", description: "Search query" },
      },
      required: ["query"],
    },
  },
  web_fetch: {
    description:
      "Fetch a web page and return its readable text (HTML stripped, truncated). Use after web_search to read a promising result.",
    parameters: {
      type: "object",
      properties: {
        url: { type: "string", description: "Absolute https URL" },
      },
      required: ["url"],
    },
  },
};

export function isServerTool(name: string): boolean {
  return name === "web_search" || name === "web_fetch";
}

export async function runServerTool(
  name: string,
  argsJson: string,
  tavilyKey?: string,
): Promise<string> {
  let args: { query?: string; url?: string };
  try {
    args = JSON.parse(argsJson);
  } catch {
    return JSON.stringify({ ok: false, error: "invalid JSON arguments" });
  }

  try {
    if (name === "web_search") {
      const query = (args.query || "").trim();
      if (!query) return JSON.stringify({ ok: false, error: "query required" });
      const results = tavilyKey
        ? await tavilySearch(query, tavilyKey)
        : await ddgSearch(query);
      return JSON.stringify({ ok: true, results });
    }
    if (name === "web_fetch") {
      const url = (args.url || "").trim();
      if (!/^https:\/\//i.test(url)) {
        return JSON.stringify({ ok: false, error: "https URL required" });
      }
      const text = await readableText(url);
      return JSON.stringify({ ok: true, text });
    }
    return JSON.stringify({ ok: false, error: `unknown server tool ${name}` });
  } catch (e) {
    return JSON.stringify({ ok: false, error: (e as Error).message });
  }
}

async function tavilySearch(
  query: string,
  key: string,
): Promise<Array<{ title: string; url: string; snippet: string }>> {
  const res = await fetch("https://api.tavily.com/search", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      api_key: key,
      query,
      max_results: SEARCH_RESULTS_LIMIT,
      search_depth: "basic",
    }),
  });
  if (!res.ok) throw new Error(`tavily HTTP ${res.status}`);
  const data = await res.json() as {
    results?: Array<{ title?: string; url?: string; content?: string }>;
  };
  return (data.results || []).slice(0, SEARCH_RESULTS_LIMIT).map((r) => ({
    title: r.title || "",
    url: r.url || "",
    snippet: (r.content || "").slice(0, 300),
  }));
}

/** DuckDuckGo lite HTML scrape (keyless fallback; best-effort). */
async function ddgSearch(
  query: string,
): Promise<Array<{ title: string; url: string; snippet: string }>> {
  const res = await fetch("https://lite.duckduckgo.com/lite/", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      "user-agent": "Mozilla/5.0 (compatible; ZafiroCloudBrain/1.0)",
    },
    body: new URLSearchParams({ q: query }).toString(),
  });
  if (!res.ok) throw new Error(`duckduckgo HTTP ${res.status}`);
  const html = await res.text();

  const results: Array<{ title: string; url: string; snippet: string }> = [];
  const linkRe = /<a[^>]+class="result-link"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
  const snippetRe = /<td[^>]*class="result-snippet"[^>]*>([\s\S]*?)<\/td>/g;
  const snippets: string[] = [];
  let sm: RegExpExecArray | null;
  while ((sm = snippetRe.exec(html)) !== null) {
    snippets.push(stripTags(sm[1]));
  }
  let lm: RegExpExecArray | null;
  let i = 0;
  while ((lm = linkRe.exec(html)) !== null && results.length < SEARCH_RESULTS_LIMIT) {
    const url = decodeDdgHref(lm[1]);
    if (!url) continue;
    results.push({
      title: stripTags(lm[2]).slice(0, 160),
      url,
      snippet: (snippets[i] || "").slice(0, 300),
    });
    i++;
  }
  return results;
}

function decodeDdgHref(href: string): string {
  // lite.duckduckgo.com links may be /l/?uddg=<encoded>
  try {
    if (href.includes("uddg=")) {
      const u = new URL("https://lite.duckduckgo.com" + href);
      const target = u.searchParams.get("uddg");
      return target ? decodeURIComponent(target) : "";
    }
    return href.startsWith("http") ? href : "https://lite.duckduckgo.com" + href;
  } catch {
    return "";
  }
}

function stripTags(html: string): string {
  return html
    .replace(/<[^>]*>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

async function readableText(url: string): Promise<string> {
  const res = await fetch(url, {
    headers: { "user-agent": "Mozilla/5.0 (compatible; ZafiroCloudBrain/1.0)" },
    redirect: "follow",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const contentType = res.headers.get("content-type") || "";
  if (!contentType.includes("text/")) {
    return `(non-text content: ${contentType}, ${res.headers.get("content-length") || "?"} bytes)`;
  }
  const html = await res.text();
  const text = stripTags(
    html
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " "),
  );
  return text.slice(0, FETCH_TEXT_LIMIT);
}
