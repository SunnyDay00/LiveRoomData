/**
 * Cloudflare Worker for LiveRoomData
 *
 * Requirements:
 * 1. D1 Database bound to variable `DB` in wrangler.toml
 * 2. Table `live_room_records` created using schema.sql
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Endpoint: POST /upload
    if (request.method === "POST" && url.pathname === "/upload") {
      return await handleUpload(request, env);
    }

    // Endpoint: GET /query (Simple debug/view)
    if (request.method === "GET" && url.pathname === "/query") {
      return await handleQuery(request, env);
    }

    return new Response("Not Found", { status: 404 });
  },
};

async function handleUpload(request, env) {
  try {
    let data = {};
    const contentType = request.headers.get("content-type") || "";

    if (contentType.includes("application/json")) {
      data = await request.json();
    } else if (contentType.includes("form")) {
      const formData = await request.formData();
      for (const entry of formData.entries()) {
        data[entry[0]] = entry[1];
      }
    } else {
      // Fallback or error
      return new Response(JSON.stringify({ success: false, error: "Unsupported Content-Type" }), {
          headers: { "Content-Type": "application/json" }
      });
    }

    // Validate required fields (optional but good practice)
    // if (!data.homeid) return new Response("Missing homeid", { status: 400 });

    const stmt = env.DB.prepare(
      `INSERT INTO live_room_records (
        app_name, homeid, homename, fansnumber, homeip,
        dayuesenumber, monthuesenumber, ueseid, uesename,
        consumption, ueseip, summaryconsumption, record_time
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      data.app_name || "",
      data.homeid || "",
      data.homename || "",
      data.fansnumber || "",
      data.homeip || "",
      data.dayuesenumber || "",
      data.monthuesenumber || "",
      data.ueseid || "",
      data.uesename || "",
      data.consumption || "",
      data.ueseip || "",
      data.summaryconsumption || "",
      data.record_time || ""
    );

    const info = await stmt.run();

    return new Response(JSON.stringify({ success: true, info }), {
      headers: { "Content-Type": "application/json" },
    });

  } catch (err) {
    return new Response(JSON.stringify({ success: false, error: err.message }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
}

async function handleQuery(request, env) {
  try {
    const { results } = await env.DB.prepare("SELECT * FROM live_room_records ORDER BY id DESC LIMIT 20").all();
    return new Response(JSON.stringify(results, null, 2), {
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: err.message }), { status: 500 });
  }
}
