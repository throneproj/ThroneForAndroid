package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.IChainStepCallback;
import io.nekohasekai.sagernet.aidl.ISubscriptionCallback;
import io.nekohasekai.sagernet.aidl.ITestSessionCallback;
import io.nekohasekai.sagernet.bg.test.TestSpec;

interface ICoreService {
  // Test sessions: returns the session id, -1 while another session runs; stopTests stops the running one.
  int startTest(in TestSpec spec, ITestSessionCallback cb);
  void stopTests();
  int runningTestSession();

  // Chain editor hop test: URL-tests the unsaved hops (top first) of a chain in group gid, storing nothing; false while
  // a test session runs, else cb gets one onDone. stopChainStepTest stops it and leaves a session alone.
  boolean startChainStepTest(in long[] hops, long gid, IChainStepCallback cb);
  void stopChainStepTest();

  // Subscriptions (desktop GroupUpdater queue).
  void refreshGroup(long gid, boolean showDiff);
  void refreshAll(boolean onlyAllowed);
  long subscribeUrl(String url, String name, boolean autoUpdate);
  void importUrl(String url, long gid);
  void importFile(String path, long gid);
  String groupAction(long gid, String action);
  String resolveProfiles(in long[] ids);
  void registerSubscriptionCallback(ISubscriptionCallback cb);
  void unregisterSubscriptionCallback(ISubscriptionCallback cb);

  // WARP: blocks up to 10 s + 10 s per API host; returns JSON (see WarpRegistration). The core ignores the TLS
  // fragment options with a proxy; tlsFragmentFallbackDelay is a Go duration, "" = 500ms.
  String warpRegister(String tunnelType, String proxy, in String[] apiHosts, boolean tlsFragment,
      boolean tlsRecordFragment, String tlsFragmentFallbackDelay);

  // AmneziaWG I1..I5 from a QUIC handshake to domain, built offline; returns JSON (see QuicSignature).
  String genQuicSignature(String domain);

  // Performance profile of :bg: blocks for the window (at most 60 s) or until stopDiagnostics, attaches each file of
  // attachmentPaths by its name and writes the zip to outputPath; returns "" or the error (see CoreDiagnostics).
  String captureDiagnostics(int durationMs, boolean contention, boolean trace, in String[] attachmentPaths, String outputPath);
  void stopDiagnostics();

  // Rule tester: walks the running instance's route rules, or a routing profile's config started stripped; blocks up
  // to 30 s plus the build. Request and reply are JSON (see RouteTester).
  String testRoute(String requestJson);
}
