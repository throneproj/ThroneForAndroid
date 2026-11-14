package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot;

// Streams one test session of ICoreService.startTest. Latency codes follow the desktop: >0 ms, -1 failed,
// -2 connected but the probe failed, 0 aborted (untested). Speeds are the core's strings ("N/A" on failure).
// An OpenVPN/OpenConnect URL failure is reported again as -2 once the core finds its tunnel up. A service check's
// result is stored in profiles.service_checks; only its error ("" = checked) comes here.
oneway interface ITestSessionCallback {
  void onStarted(int session, int kind, int total);
  void onUrlResult(long profileId, int latency, String error);
  void onIpResult(long profileId, String ip, String country, String error);
  void onSpeedProgress(in SpeedTestSnapshot snapshot);
  void onSpeedResult(long profileId, String dl, String ul, int latency, String country, String error);
  void onServiceResult(long profileId, String error);
  void onDone(int session, boolean cancelled);
}
