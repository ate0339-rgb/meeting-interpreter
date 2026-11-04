package com.hm.meetingnote;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * One folder per meeting: meta.json (title, times, words, audio parts), segs.json (EN/KO lines)
 * and audio-NNN.m4a parts. Everything stays on the phone until the user shares it.
 */
final class SessionStore {
 private final File root;

 SessionStore(Context context) {
  root = new File(context.getFilesDir(), "sessions");
  root.mkdirs();
 }

 File dir(String id) { return new File(root, id.replaceAll("[^A-Za-z0-9_-]", "")); }

 synchronized JSONObject create(String lang, String title) throws Exception {
  String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
  dir(id).mkdirs();
  JSONObject meta = new JSONObject().put("id", id).put("title", title == null ? "" : title)
   .put("created", System.currentTimeMillis()).put("activeMs", 0).put("lang", lang)
   .put("words", new JSONArray()).put("audio", new JSONArray()).put("segCount", 0).put("first", "");
  write(new File(dir(id), "meta.json"), meta.toString());
  write(new File(dir(id), "segs.json"), "[]");
  return meta;
 }

 synchronized JSONObject meta(String id) throws Exception { return new JSONObject(read(new File(dir(id), "meta.json"))); }
 synchronized JSONArray segs(String id) throws Exception {
  File f = new File(dir(id), "segs.json");
  return f.isFile() ? new JSONArray(read(f)) : new JSONArray();
 }
 interface Change { void apply(JSONObject meta) throws Exception; }
 /** Read-modify-write so the recorder and the screen never overwrite each other's fields. */
 synchronized JSONObject update(String id, Change change) throws Exception {
  JSONObject m = meta(id);
  change.apply(m);
  write(new File(dir(id), "meta.json"), m.toString());
  return m;
 }
 synchronized void saveSegs(String id, JSONArray segs) throws Exception { write(new File(dir(id), "segs.json"), segs.toString()); }

 synchronized JSONArray list() {
  JSONArray out = new JSONArray();
  File[] dirs = root.listFiles(File::isDirectory);
  if (dirs == null) return out;
  Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
  for (File d : dirs) {
   try { out.put(meta(d.getName())); } catch (Exception ignored) { }
  }
  return out;
 }

 synchronized void delete(String id) {
  File d = dir(id);
  File[] files = d.listFiles();
  if (files != null) for (File f : files) f.delete();
  d.delete();
 }

 /** Transcript as Markdown: EN line, KO line, with timestamps. Also the body of the email. */
 synchronized String markdown(String id) throws Exception {
  JSONObject m = meta(id);
  JSONArray s = segs(id);
  SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA), clock = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
  StringBuilder o = new StringBuilder();
  o.append("# ").append(title(m)).append("\n\n");
  o.append("- 일시: ").append(day.format(new Date(m.getLong("created")))).append("\n");
  o.append("- 녹음 시간: ").append(m.getLong("activeMs") / 60000).append("분\n");
  o.append("- 문장 수: ").append(s.length()).append("\n");
  o.append("- 인식 언어: ").append(m.optString("lang")).append("\n");
  JSONArray audio = m.optJSONArray("audio");
  if (audio != null && audio.length() > 0) o.append("- 녹음 파일: ").append(audio.join(", ").replace("\"", "")).append("\n");
  JSONArray words = m.optJSONArray("words");
  if (words != null && words.length() > 0) {
   o.append("\n## 단어장\n\n");
   for (int i = 0; i < words.length(); i++) {
    JSONObject w = words.getJSONObject(i);
    o.append("- **").append(w.optString("word")).append("** — ").append(w.optString("meaning").replace('\n', ' ')).append("\n");
   }
  }
  o.append("\n## 대화 기록\n\n");
  for (int i = 0; i < s.length(); i++) {
   JSONObject x = s.getJSONObject(i);
   o.append("**[").append(clock.format(new Date(x.getLong("t")))).append("]** ").append(x.optString("en")).append("\n");
   o.append("> ").append(x.optString("ko")).append("\n\n");
  }
  return o.toString();
 }

 String title(JSONObject m) {
  String t = m.optString("title").trim();
  return t.isEmpty() ? new SimpleDateFormat("M월 d일 (E) HH:mm 미팅", Locale.KOREA).format(new Date(m.optLong("created"))) : t;
 }

 private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
 private static void write(File f, String s) throws IOException {
  File tmp = new File(f.getPath() + ".tmp");
  Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
  Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
 }
}
