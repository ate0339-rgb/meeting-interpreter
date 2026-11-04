package com.hm.meetingnote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Hosts the page (assets/app.html) and bridges it to RecorderService and the session files. */
public class MainActivity extends Activity {
 private static final int REQ_PERMS = 1;
 private WebView web;
 private SessionStore store;
 private SharedPreferences prefs;
 private String[] pendingStart;
 private RecorderService.Listener listener;

 @Override protected void onCreate(Bundle saved) {
  super.onCreate(saved);
  store = new SessionStore(this);
  prefs = getSharedPreferences("prefs", MODE_PRIVATE);
  web = new WebView(this);
  android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
  frame.addView(web);
  setContentView(frame);
  boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
  int bg = night ? 0xFF11171D : 0xFFF2F4F6;
  web.setBackgroundColor(bg);
  getWindow().getDecorView().setBackgroundColor(bg);
  WindowInsetsController wic = getWindow().getInsetsController();
  if (wic != null) wic.setSystemBarsAppearance(night ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
   WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
  // keep the page clear of the status bar, navigation bar and keyboard (WebView ignores its own padding)
  frame.setBackgroundColor(bg);
  frame.setOnApplyWindowInsetsListener((v, insets) -> {
   Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
   v.setPadding(i.left, i.top, i.right, i.bottom);
   return WindowInsets.CONSUMED;
  });

  WebSettings s = web.getSettings();
  s.setJavaScriptEnabled(true);
  s.setDomStorageEnabled(true);
  s.setAllowFileAccess(false);
  s.setAllowContentAccess(false);
  WebViewAssetLoader loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
  web.setWebViewClient(new WebViewClient() {
   @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return loader.shouldInterceptRequest(request.getUrl()); }
   @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    Uri u = request.getUrl();
    if ("appassets.androidplatform.net".equals(u.getHost())) return false;
    startActivity(new Intent(Intent.ACTION_VIEW, u));
    return true;
   }
  });
  web.addJavascriptInterface(new Bridge(), "MeetingNative");
  web.loadUrl("https://appassets.androidplatform.net/assets/app.html");

  listener = e -> web.evaluateJavascript("window.__mn&&window.__mn(" + e + ")", null);
  RecorderService.listener = listener;
  getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
   () -> web.evaluateJavascript("window.__back?window.__back():false", r -> { if (!"true".equals(r)) moveTaskToBack(true); }));
 }

 @Override protected void onResume() {
  super.onResume();
  RecorderService.listener = listener;
  web.evaluateJavascript("window.__resume&&window.__resume()", null);
 }

 @Override protected void onDestroy() {
  if (RecorderService.listener == listener) RecorderService.listener = null;
  web.destroy();
  super.onDestroy();
 }

 // ---------- permissions ----------
 private void startRecording(String session, String lang) {
  ArrayList<String> need = new ArrayList<>();
  if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
  if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
  if (!need.isEmpty()) { pendingStart = new String[]{session, lang}; requestPermissions(need.toArray(new String[0]), REQ_PERMS); return; }
  RecorderService.send(this, RecorderService.ACTION_START, session, lang);
 }

 @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
  super.onRequestPermissionsResult(code, perms, results);
  if (code != REQ_PERMS || pendingStart == null) return;
  String[] p = pendingStart;
  pendingStart = null;
  if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecorderService.send(this, RecorderService.ACTION_START, p[0], p[1]);
  else web.evaluateJavascript("window.__mn&&window.__mn({type:'error',text:'마이크 권한을 허용해야 녹음할 수 있어요. 설정 → 앱 → 미팅 통역 노트 → 권한에서 켤 수 있어요.'})", null);
 }

 // ---------- sharing ----------
 private void share(String id, String how) throws Exception {
  JSONObject meta = store.meta(id);
  File dir = store.dir(id);
  String md = store.markdown(id);
  File mdFile = new File(dir, "transcript.md");
  Files.write(mdFile.toPath(), md.getBytes(StandardCharsets.UTF_8));
  File json = new File(dir, "meeting.json");
  Files.write(json.toPath(), new JSONObject().put("meta", meta).put("segments", store.segs(id)).toString(1).getBytes(StandardCharsets.UTF_8));

  ArrayList<Uri> uris = new ArrayList<>();
  uris.add(FileProvider.getUriForFile(this, "com.hm.meetingnote.files", mdFile));
  uris.add(FileProvider.getUriForFile(this, "com.hm.meetingnote.files", json));
  JSONArray audio = meta.optJSONArray("audio");
  if (audio != null) for (int i = 0; i < audio.length(); i++) {
   File f = new File(dir, audio.getString(i));
   if (f.isFile() && f.length() > 1024) uris.add(FileProvider.getUriForFile(this, "com.hm.meetingnote.files", f));
  }
  String date = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date(meta.getLong("created")));
  Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE)
   .setType("*/*")
   .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
   .putExtra(Intent.EXTRA_SUBJECT, "[미팅노트] " + store.title(meta) + " (" + date + ")")
   .putExtra(Intent.EXTRA_TEXT, md.length() > 150_000 ? md.substring(0, 150_000) + "\n\n(이후 내용은 첨부한 transcript.md에 있어요)" : md)
   .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  if ("mail".equals(how)) {
   String to = prefs.getString("email", "");
   if (!to.isEmpty()) i.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
   i.setPackage("com.google.android.gm");
   if (i.resolveActivity(getPackageManager()) == null) i.setPackage(null);
  }
  startActivity(i.getPackage() != null ? i : Intent.createChooser(i, "미팅 기록 보내기"));
 }

 /** Methods the page calls. They run on a WebView binder thread; UI work hops to the main thread. */
 final class Bridge {
  @JavascriptInterface public String state() { return RecorderService.state().toString(); }
  @JavascriptInterface public String sessions() { return store.list().toString(); }

  @JavascriptInterface public String session(String id) {
   try { return new JSONObject().put("meta", store.meta(id)).put("segs", store.segs(id)).toString(); }
   catch (Exception e) { return "null"; }
  }

  @JavascriptInterface public void start(String session, String lang) { runOnUiThread(() -> startRecording(session, lang)); }
  @JavascriptInterface public void pause() { RecorderService.send(MainActivity.this, RecorderService.ACTION_PAUSE, null, null); }
  @JavascriptInterface public void resume() { RecorderService.send(MainActivity.this, RecorderService.ACTION_RESUME, null, null); }
  @JavascriptInterface public void stop() { RecorderService.send(MainActivity.this, RecorderService.ACTION_STOP, null, null); }

  @JavascriptInterface public boolean setTitle(String id, String title) {
   try { store.update(id, m -> m.put("title", title)); return true; } catch (Exception e) { return false; }
  }

  @JavascriptInterface public boolean addWord(String id, String word) {
   try {
    JSONObject w = new JSONObject(word);
    store.update(id, m -> {
     JSONArray words = m.optJSONArray("words");
     if (words == null) { words = new JSONArray(); m.put("words", words); }
     for (int i = 0; i < words.length(); i++) if (words.getJSONObject(i).optString("word").equalsIgnoreCase(w.optString("word"))) return;
     words.put(w);
    });
    return true;
   } catch (Exception e) { return false; }
  }

  @JavascriptInterface public boolean remove(String id) {
   JSONObject st = RecorderService.state();
   if (st.optBoolean("recording") && id.equals(st.optString("session"))) return false;
   store.delete(id);
   return true;
  }

  @JavascriptInterface public void share(String id, String how) {
   runOnUiThread(() -> {
    try { MainActivity.this.share(id, how); }
    catch (Exception e) { Toast.makeText(MainActivity.this, "보내기를 준비하지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
   });
  }

  @JavascriptInterface public String pref(String key) { return prefs.getString(key, ""); }
  @JavascriptInterface public void setPref(String key, String value) { prefs.edit().putString(key, value).apply(); }
  @JavascriptInterface public void toast(String text) { runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show()); }
 }
}
