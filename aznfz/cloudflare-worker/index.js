import { neon } from '@neondatabase/serverless';

export default {
  async fetch(request, env) {
    // CORS headers
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-API-Key',
    };

    // Handle preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    // Only allow POST to /upload
    if (request.method !== 'POST') {
      return new Response('Method not allowed', { status: 405, headers: corsHeaders });
    }

    try {
      // Parse request body as Form Data (to support legacy Android script)
      const formData = await request.formData();
      const data = {};
      for (const [key, value] of formData.entries()) {
        data[key] = value;
      }

      // Validate API key
      const clientApiKey = request.headers.get('X-API-Key');
      if (clientApiKey !== env.CLIENT_API_KEY) {
        return new Response(JSON.stringify({ error: 'Invalid API key' }), {
          status: 401,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Connect to Neon with WRITE-ONLY role
      const sql = neon(env.WRITE_DATABASE_URL);

      // Calculate Beijing Time (UTC+8)
      const now = new Date();
      const beijingTime = new Date(now.getTime() + 8 * 60 * 60 * 1000);
      const record_time = beijingTime.toISOString().replace('T', ' ').substring(0, 23);

      // Insert data
      const result = await sql`
        INSERT INTO live_room_data (
          app_name, homeid, homename, fansnumber, homeip,
          dayuesenumber, weekuesenumber, monthuesenumber,
          ueseid, uesename, consumption, summaryconsumption, ueseip,
          record_time
        ) VALUES (
          ${data.app_name},
          ${data.homeid},
          ${data.homename},
          ${data.fansnumber},
          ${data.homeip},
          ${data.dayuesenumber},
          ${data.weekuesenumber},
          ${data.monthuesenumber},
          ${data.ueseid},
          ${data.uesename},
          ${data.consumption},
          ${data.summaryconsumption},
          ${data.ueseip},
          ${record_time}::timestamp
        )
        RETURNING id
      `;

      return new Response(JSON.stringify({ 
        success: true, 
        id: result[0].id 
      }), {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });

    } catch (error) {
      console.error('Database error:', error);
      return new Response(JSON.stringify({ 
        error: 'Database insertion failed',
        message: error.message 
      }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
  }
};

