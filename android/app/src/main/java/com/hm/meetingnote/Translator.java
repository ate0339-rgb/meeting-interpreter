package com.hm.meetingnote;

import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** English → Korean through Google's free web translation endpoint (same one the web version uses). */
final class Translator {
 private Translator() { }

 static String toKorean(String text) throws IOException {
  IOException last = null;
  for (int attempt = 0; attempt < 3; attempt++) {
   try { return attempt < 2 ? google(text) : myMemory(text); }
   catch (IOException e) { last = e; try { Thread.sleep(800L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; } }
  }
  throw last;
 }

 private static String google(String text) throws IOException {
  try {
   JSONArray parts = new JSONArray(get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ko&dt=t&q=" + URLEncoder.encode(text, "UTF-8"))).getJSONArray(0);
   StringBuilder s = new StringBuilder();
   for (int i = 0; i < parts.length(); i++) s.append(parts.getJSONArray(i).optString(0));
   return s.toString();
  } catch (org.json.JSONException e) { throw new IOException("translate parse", e); }
 }

 /** Backup when Google refuses: MyMemory's free public API (limited daily volume). */
 private static String myMemory(String text) throws IOException {
  try {
   org.json.JSONObject j = new org.json.JSONObject(get("https://api.mymemory.translated.net/get?langpair=en|ko&q=" + URLEncoder.encode(text.length() > 500 ? text.substring(0, 500) : text, "UTF-8")));
   if (j.optInt("responseStatus") != 200) throw new IOException("mymemory " + j.optInt("responseStatus"));
   return j.getJSONObject("responseData").getString("translatedText");
  } catch (org.json.JSONException e) { throw new IOException("mymemory parse", e); }
 }

 private static String get(String address) throws IOException {
  HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
  c.setConnectTimeout(8000);
  c.setReadTimeout(8000);
  c.setInstanceFollowRedirects(false);
  // Java's default "Dalvik/…" identity is refused by the Google endpoint; name the app instead
  c.setRequestProperty("User-Agent", "MeetingNote/1.0 (Android)");
  try {
   if (c.getResponseCode() != 200) throw new IOException("translate " + c.getResponseCode());
   try (InputStream in = c.getInputStream()) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) > 0) out.write(b, 0, n);
    return out.toString(StandardCharsets.UTF_8.name());
   }
  } finally { c.disconnect(); }
 }
}
