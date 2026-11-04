package com.hm.meetingnote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground microphone service. One AudioRecord feeds both the .m4a recording and the speech
 * recognizer (through EXTRA_AUDIO_SOURCE), so recording keeps going with the screen off and the
 * two never compete for the microphone. Lines are translated and saved here, not in the page.
 */
public class RecorderService extends Service {
 static final String TAG = "MeetingNote";
 static final String ACTION_START = "start", ACTION_PAUSE = "pause", ACTION_RESUME = "resume", ACTION_STOP = "stop";
 private static final int RATE = 16000, CHUNK = 3200; // 100 ms of 16-bit mono
 private static final long ROTATE_MS = 10 * 60_000L;   // finalize an audio part every 10 minutes
 private static final int NOTE_ID = 7;

 interface Listener { void event(JSONObject e); }
 static volatile Listener listener;
 static volatile RecorderService instance;

 private final Handler main = new Handler(Looper.getMainLooper());
 private final ExecutorService io = Executors.newSingleThreadExecutor();
 private final ExecutorService translate = Executors.newSingleThreadExecutor();
 private SessionStore store;
 private PowerManager.WakeLock wake;

 private volatile String sessionId;
 private volatile String lang = "en-US";
 private JSONArray segs = new JSONArray();
 private volatile boolean recording, paused;
 private long activeMs, activeSince;
 private int segCount;
 private volatile String partial = "";

 private Thread audioThread;
 private AacWriter writer;
 private long partStartedMs;

 // recognizer ladder: on-device segmented → on-device → default segmented → default → record only
 private static final String[] MODES = {"ondevice-segmented", "ondevice", "default-segmented", "default", "off"};
 private int mode;
 private int failsWithoutResult;
 private boolean modelDownloadAsked;
 private SpeechRecognizer recognizer;
 private volatile OutputStream sink;
 private ParcelFileDescriptor readEnd; // handed to the recognizer; closed when that session ends
 private final BlockingQueue<byte[]> feed = new ArrayBlockingQueue<>(80);
 private Thread feeder;
 private volatile String recognizerNote = "";

 static void send(Context c, String action, String sessionId, String lang) {
  Intent i = new Intent(c, RecorderService.class).setAction(action);
  if (sessionId != null) i.putExtra("session", sessionId);
  if (lang != null) i.putExtra("lang", lang);
  if (ACTION_START.equals(action)) c.startForegroundService(i); else c.startService(i);
 }

 @Override public void onCreate() {
  super.onCreate();
  instance = this;
  store = new SessionStore(this);
  NotificationManager nm = getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel("rec", "녹음", NotificationManager.IMPORTANCE_LOW));
  wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeetingNote:rec");
 }

 @Override public int onStartCommand(Intent intent, int flags, int startId) {
  String action = intent == null ? null : intent.getAction();
  if (ACTION_START.equals(action)) {
   startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
   begin(intent.getStringExtra("session"), intent.getStringExtra("lang"));
  } else if (ACTION_PAUSE.equals(action)) pause();
  else if (ACTION_RESUME.equals(action)) resume();
  else if (ACTION_STOP.equals(action)) finish();
  else if (!recording) stopSelf();
  return START_NOT_STICKY;
 }

 // ---------- session lifecycle ----------
 private void begin(String requested, String language) {
  if (recording) { finishInternal(); }
  try {
   if (language != null && !language.isEmpty()) lang = language;
   JSONObject meta = requested == null || requested.isEmpty() || !store.dir(requested).isDirectory()
    ? store.create(lang, "") : store.meta(requested);
   sessionId = meta.getString("id");
   lang = meta.optString("lang", lang);
   segs = store.segs(sessionId);
   activeMs = meta.optLong("activeMs");
   segCount = segs.length();
   // lines whose translation failed earlier (e.g. no network) get another try
   for (int i = 0; i < segs.length(); i++) if (segs.getJSONObject(i).optString("ko").isEmpty()) translateLater(sessionId, segs.getJSONObject(i));
  } catch (Exception e) {
   Log.e(TAG, "SESSION_OPEN_FAILED", e);
   emit("error", "미팅을 열지 못했어요: " + e.getMessage());
   stopSelf();
   return;
  }
  recording = true;
  paused = false;
  mode = 0; failsWithoutResult = 0;
  if (!wake.isHeld()) wake.acquire();
  startAudio();
  main.post(this::startRecognition);
  emitState();
 }

 private void pause() {
  if (!recording || paused) return;
  paused = true;
  stopAudio();
  main.post(this::stopRecognition);
  updateNotification();
  emitState();
 }

 private void resume() {
  if (!recording || !paused) return;
  paused = false;
  startAudio();
  main.post(this::startRecognition);
  updateNotification();
  emitState();
 }

 private void finish() {
  finishInternal();
  stopForeground(STOP_FOREGROUND_REMOVE);
  stopSelf();
 }

 private void finishInternal() {
  if (!recording) return;
  recording = false;
  paused = false;
  stopAudio();
  main.post(this::stopRecognition);
  if (wake.isHeld()) wake.release();
  final String id = sessionId;
  io.execute(() -> { try { store.update(id, m -> m.put("ended", System.currentTimeMillis())); } catch (Exception ignored) { } });
  emitState();
 }

 @Override public void onDestroy() {
  finishInternal();
  if (instance == this) instance = null;
  io.shutdown();
  translate.shutdown();
  super.onDestroy();
 }

 @Override public IBinder onBind(Intent intent) { return null; }

 // ---------- audio ----------
 private void startAudio() {
  if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
   emit("error", "마이크 권한이 없어요.");
   return;
  }
  activeSince = System.currentTimeMillis();
  audioThread = new Thread(this::audioLoop, "MeetingNote-audio");
  audioThread.start();
  feeder = new Thread(this::feedLoop, "MeetingNote-feed");
  feeder.start();
 }

 private void stopAudio() {
  Thread t = audioThread;
  audioThread = null;
  if (t != null) { t.interrupt(); try { t.join(3000); } catch (InterruptedException ignored) { } }
  Thread f = feeder;
  feeder = null;
  if (f != null) f.interrupt();
  if (activeSince > 0) { activeMs += System.currentTimeMillis() - activeSince; activeSince = 0; }
  saveMeta();
 }

 /**
  * Test builds only: if files/debug-input.wav (16 kHz mono 16-bit) exists, play it into the pipeline
  * in real time instead of the microphone, then continue with silence. Lets us test without sound.
  */
 private java.io.InputStream debugInput() {
  if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
  File f = new File(getFilesDir(), "debug-input.wav");
  if (!f.isFile()) return null;
  try { java.io.InputStream in = new java.io.FileInputStream(f); in.skip(44); Log.i(TAG, "DEBUG_INPUT " + f.length()); return in; }
  catch (IOException e) { return null; }
 }

 private void debugLoop(java.io.InputStream in) {
  byte[] buf = new byte[CHUNK];
  long next = System.currentTimeMillis();
  try {
   while (audioThread == Thread.currentThread()) {
    int n = in == null ? -1 : in.read(buf);
    if (n <= 0) { if (in != null) { try { in.close(); } catch (IOException ignored) { } in = null; } java.util.Arrays.fill(buf, (byte) 0); n = CHUNK; }
    if (writer == null || System.currentTimeMillis() - partStartedMs > ROTATE_MS) rotateWriter();
    if (writer != null) writer.write(buf, n);
    if (sink != null) feed.offer(java.util.Arrays.copyOf(buf, n));
    next += n * 1000L / (RATE * 2);
    long wait = next - System.currentTimeMillis();
    if (wait > 0) Thread.sleep(wait);
   }
  } catch (InterruptedException | IOException ignored) {
  } finally { closeWriter(); Log.i(TAG, "AUDIO_STOPPED"); }
 }

 private void audioLoop() {
  java.io.InputStream debug = debugInput();
  if (debug != null) { debugLoop(debug); return; }
  int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
  AudioRecord rec;
  try {
   rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 8));
  } catch (SecurityException e) { emit("error", "마이크 권한이 없어요."); return; }
  if (rec.getState() != AudioRecord.STATE_INITIALIZED) { emit("error", "마이크를 열지 못했어요."); rec.release(); return; }
  rec.startRecording();
  Log.i(TAG, "AUDIO_STARTED");
  byte[] buf = new byte[CHUNK];
  try {
   while (audioThread == Thread.currentThread()) {
    int n = rec.read(buf, 0, buf.length);
    if (n <= 0) continue;
    if (writer == null || System.currentTimeMillis() - partStartedMs > ROTATE_MS) rotateWriter();
    if (writer != null) writer.write(buf, n);
    if (sink != null) feed.offer(java.util.Arrays.copyOf(buf, n)); // drop when the recognizer lags
   }
  } catch (Exception e) {
   Log.e(TAG, "AUDIO_LOOP_FAILED", e);
   emit("error", "녹음 중 문제가 생겼어요: " + e.getMessage());
  } finally {
   rec.stop();
   rec.release();
   closeWriter();
   Log.i(TAG, "AUDIO_STOPPED");
  }
 }

 private void rotateWriter() {
  closeWriter();
  try {
   JSONObject m = store.meta(sessionId);
   JSONArray audio = m.optJSONArray("audio");
   String name = String.format(Locale.US, "audio-%03d.m4a", (audio == null ? 0 : audio.length()) + 1);
   writer = new AacWriter(new File(store.dir(sessionId), name), RATE);
   partStartedMs = System.currentTimeMillis();
   store.update(sessionId, x -> x.getJSONArray("audio").put(name));
  } catch (Exception e) {
   writer = null;
   Log.e(TAG, "AUDIO_FILE_FAILED", e);
   emit("error", "녹음 파일을 만들지 못했어요.");
  }
 }

 private void closeWriter() {
  AacWriter w = writer;
  writer = null;
  if (w != null) w.close();
 }

 private void feedLoop() {
  try {
   while (feeder == Thread.currentThread()) {
    byte[] b = feed.take();
    OutputStream s = sink;
    if (s == null) continue;
    try { s.write(b); } catch (IOException e) { if (sink == s) sink = null; }
   }
  } catch (InterruptedException ignored) { }
 }

 // ---------- speech recognition (main thread) ----------
 private void startRecognition() {
  if (!recording || paused) return;
  if (MODES[mode].equals("off")) { note("실시간 자막을 쓸 수 없어 녹음만 하고 있어요"); return; }
  closeSink();
  feed.clear();
  try {
   ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
   this.readEnd = pipe[0];
   sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
  } catch (IOException e) { nextMode("pipe"); return; }
  final ParcelFileDescriptor readEnd = this.readEnd;
  boolean onDevice = MODES[mode].startsWith("ondevice");
  if (recognizer == null) {
   try {
    if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) { nextMode("no on-device"); return; }
    recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this) : SpeechRecognizer.createSpeechRecognizer(this);
    recognizer.setRecognitionListener(new Listen());
   } catch (Exception e) { nextMode("create " + e.getClass().getSimpleName()); return; }
  }
  Intent i = intent();
  i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd);
  i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
  i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
  i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
  if (MODES[mode].endsWith("segmented")) i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
  try {
   recognizer.startListening(i);
   Log.i(TAG, "RECOGNIZER_START mode=" + MODES[mode]);
  } catch (Exception e) { nextMode("start " + e.getClass().getSimpleName()); return; }
 }

 private Intent intent() {
  Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
  i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
  i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
  i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
  i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
  return i;
 }

 private void stopRecognition() {
  closeSink();
  if (recognizer != null) {
   try { recognizer.cancel(); } catch (Exception ignored) { }
   try { recognizer.destroy(); } catch (Exception ignored) { }
   recognizer = null;
  }
  partial = "";
 }

 private void restartSoon(long delay) {
  closeSink();
  main.postDelayed(this::startRecognition, delay);
 }

 private void nextMode(String why) {
  Log.w(TAG, "RECOGNIZER_FALLBACK from=" + MODES[mode] + " reason=" + why);
  if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } recognizer = null; }
  if (mode < MODES.length - 1) mode++;
  failsWithoutResult = 0;
  restartSoon(300);
 }

 private void closeSink() {
  OutputStream s = sink;
  sink = null;
  if (s != null) try { s.close(); } catch (IOException ignored) { }
  ParcelFileDescriptor r = readEnd;
  readEnd = null;
  if (r != null) closeQuietly(r);
 }

 private static void closeQuietly(ParcelFileDescriptor p) { try { p.close(); } catch (IOException ignored) { } }

 private void note(String s) { recognizerNote = s; emitState(); }

 private final class Listen implements RecognitionListener {
  @Override public void onReadyForSpeech(Bundle b) { note(""); }
  @Override public void onBeginningOfSpeech() { }
  @Override public void onRmsChanged(float v) { }
  @Override public void onBufferReceived(byte[] b) { }
  @Override public void onEndOfSpeech() { }
  @Override public void onEvent(int type, Bundle b) { }

  @Override public void onPartialResults(Bundle b) {
   String t = best(b);
   if (t.isEmpty()) return;
   partial = t;
   try { emit(new JSONObject().put("type", "partial").put("text", t)); } catch (Exception ignored) { }
  }

  @Override public void onResults(Bundle b) {
   addFinal(best(b));
   if (recording && !paused) restartSoon(50);
  }

  @Override public void onSegmentResults(Bundle b) { addFinal(best(b)); }

  @Override public void onEndOfSegmentedSession() { if (recording && !paused) restartSoon(50); }

  @Override public void onError(int error) {
   Log.w(TAG, "RECOGNIZER_ERROR mode=" + MODES[mode] + " code=" + error);
   if (!recording || paused) return;
   switch (error) {
    case SpeechRecognizer.ERROR_NO_MATCH:
    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
     restartSoon(50); return;
    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
     mode = MODES.length - 1; note("음성 인식 권한이 없어 녹음만 하고 있어요"); return;
    case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
    case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
     if (MODES[mode].startsWith("ondevice") && !modelDownloadAsked && recognizer != null) {
      modelDownloadAsked = true;
      try { recognizer.triggerModelDownload(intent()); note("영어 음성 인식 모델을 내려받는 중이에요"); } catch (Exception ignored) { }
     }
     nextMode("language " + error); return;
    case SpeechRecognizer.ERROR_NETWORK:
    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
    case SpeechRecognizer.ERROR_SERVER:
    case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
     note("음성 인식 연결을 다시 시도하는 중이에요");
     restartSoon(1500); return;
    case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
     restartSoon(3000); return;
    default:
     if (++failsWithoutResult >= 3) { nextMode("error " + error); return; }
     if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } recognizer = null; }
     restartSoon(600);
   }
  }
 }

 private static String best(Bundle b) {
  if (b == null) return "";
  ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
  return r == null || r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim();
 }

 // ---------- transcript ----------
 private void addFinal(String text) {
  partial = "";
  if (text == null || text.isEmpty()) return;
  failsWithoutResult = 0;
  try {
   JSONObject last = segs.length() > 0 ? segs.getJSONObject(segs.length() - 1) : null;
   if (last != null && last.optString("en").equalsIgnoreCase(text) && System.currentTimeMillis() - last.optLong("t") < 4000) return;
   String en = Character.toUpperCase(text.charAt(0)) + text.substring(1);
   JSONObject seg = new JSONObject().put("id", Long.toString(System.nanoTime(), 36)).put("t", System.currentTimeMillis()).put("en", en).put("ko", "");
   synchronized (this) { segs.put(seg); segCount = segs.length(); }
   saveSegs();
   saveMeta();
   emit(new JSONObject().put("type", "seg").put("session", sessionId).put("seg", seg));
   updateNotification();
   translateLater(sessionId, seg);
  } catch (Exception e) { Log.e(TAG, "SEGMENT_FAILED", e); }
 }

 private void translateLater(String sid, JSONObject seg) {
  final String en = seg.optString("en");
  translate.execute(() -> {
   String ko;
   try { ko = Translator.toKorean(en); } catch (Exception e) { Log.w(TAG, "TRANSLATE_FAILED " + e.getMessage()); return; }
   try {
    synchronized (RecorderService.this) { seg.put("ko", ko); }
    if (sid.equals(sessionId)) saveSegs(); else store.saveSegs(sid, mergeKo(sid, seg));
    emit(new JSONObject().put("type", "ko").put("session", sid).put("id", seg.getString("id")).put("ko", ko));
   } catch (Exception ignored) { }
  });
 }

 private JSONArray mergeKo(String sid, JSONObject seg) throws Exception {
  JSONArray all = store.segs(sid);
  for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).optString("id").equals(seg.optString("id"))) all.getJSONObject(i).put("ko", seg.optString("ko"));
  return all;
 }

 private synchronized void saveSegs() {
  final String id = sessionId;
  final String snapshot = segs.toString();
  io.execute(() -> { try { store.saveSegs(id, new JSONArray(snapshot)); } catch (Exception e) { Log.e(TAG, "SAVE_SEGS_FAILED", e); } });
 }

 private synchronized void saveMeta() {
  final String id = sessionId;
  if (id == null) return;
  final long ms = activeMs + (activeSince > 0 ? System.currentTimeMillis() - activeSince : 0);
  final int count = segCount;
  final String first;
  try { first = segs.length() > 0 ? segs.getJSONObject(0).optString("en") : ""; } catch (Exception e) { return; }
  io.execute(() -> { try { store.update(id, m -> m.put("activeMs", ms).put("segCount", count).put("first", first)); } catch (Exception e) { Log.e(TAG, "SAVE_META_FAILED", e); } });
 }

 // ---------- state + notification ----------
 static JSONObject state() {
  RecorderService s = instance;
  JSONObject o = new JSONObject();
  try {
   if (s == null) return o.put("recording", false);
   long ms = s.activeMs + (s.activeSince > 0 ? System.currentTimeMillis() - s.activeSince : 0);
   o.put("recording", s.recording).put("paused", s.paused).put("session", s.sessionId == null ? "" : s.sessionId)
    .put("activeMs", ms).put("mode", MODES[s.mode]).put("note", s.recognizerNote).put("partial", s.partial).put("lang", s.lang);
  } catch (Exception ignored) { }
  return o;
 }

 private void emitState() {
  try { emit(state().put("type", "state")); } catch (Exception ignored) { }
 }

 private void emit(String type, String text) {
  try { emit(new JSONObject().put("type", type).put("text", text)); } catch (Exception ignored) { }
 }

 private void emit(JSONObject e) {
  Listener l = listener;
  if (l != null) main.post(() -> l.event(e));
 }

 private Notification notification() {
  PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder b = new Notification.Builder(this, "rec")
   .setSmallIcon(android.R.drawable.ic_btn_speak_now)
   .setContentTitle(paused ? "미팅 녹음 일시정지" : "미팅 녹음 중")
   .setContentText(segCount + "문장 기록됨 · 화면을 꺼도 계속 녹음해요")
   .setOngoing(true)
   .setContentIntent(open)
   .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
  if (!paused) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (activeMs + (activeSince > 0 ? System.currentTimeMillis() - activeSince : 0)));
  b.addAction(new Notification.Action.Builder(null, paused ? "계속" : "일시정지",
   PendingIntent.getService(this, 1, new Intent(this, RecorderService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)).build());
  b.addAction(new Notification.Action.Builder(null, "종료",
   PendingIntent.getService(this, 2, new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build());
  return b.build();
 }

 private void updateNotification() {
  if (recording) getSystemService(NotificationManager.class).notify(NOTE_ID, notification());
 }
}
