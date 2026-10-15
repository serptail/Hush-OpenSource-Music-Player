// Deletes the calling user's auth record. ON DELETE CASCADE removes every row they own
// (profiles, favorites, playlists + playlist_songs, listening_history, artist_follows) and every session / refresh token.
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

  // A retry loop or a stolen token must not be able to hammer this endpoint.
  const { data: allowed } = await admin.rpc("take_rate_limit", {
    p_user: user.id, p_bucket: "delete_account", p_max: 5, p_window_seconds: 3600,
  });
  if (allowed !== true) return json({ error: "rate_limited" }, 429);

  // Storage objects are not covered by ON DELETE CASCADE, and once the user is gone nobody can clean the avatar up.
  // storage.remove() reports trouble in `error` (it does not throw), so the old `.catch` never fired. Retry once, and
  // if it still fails stop BEFORE deleting the account: the app shows "couldn't delete" and the user can simply retry.
  // (A missing file is not an error.)
  let avatarError: string | null = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    const { error } = await admin.storage.from("avatars").remove([`${user.id}.webp`]);
    avatarError = error?.message ?? null;
    if (!avatarError) break;
    console.error("avatar cleanup failed", attempt, avatarError);
  }
  if (avatarError) return json({ error: "delete_failed" }, 500);

  const { error } = await admin.auth.admin.deleteUser(user.id);
  if (error) { console.error("deleteUser failed", error.message); return json({ error: "delete_failed" }, 500); }
  // iOS (phase 6): also revoke the Sign in with Apple token here.
  return json({ ok: true });
});