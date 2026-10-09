// Deletes the calling user's auth record. ON DELETE CASCADE removes every row they own.
import { createClient } from "npm:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/cors.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const url = Deno.env.get("SUPABASE_URL")!;
  const userClient = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } },
  });
  const { data: { user } } = await userClient.auth.getUser();
  if (!user) return json({ error: "unauthorized" }, 401);
  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
  // Storage objects are not covered by ON DELETE CASCADE: remove the avatar first (best effort, missing file is fine).
  await admin.storage.from("avatars").remove([`${user.id}.webp`]).catch(() => {});
  const { error } = await admin.auth.admin.deleteUser(user.id);
  if (error) return json({ error: "delete_failed" }, 500);
  // iOS (phase 6): also revoke the Sign in with Apple token here.
  return json({ ok: true });
});