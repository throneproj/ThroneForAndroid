package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;
import io.nekohasekai.sagernet.aidl.LiveStatsBatch;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  // The running instance's URL test in ms, or -2 when it failed through an OpenVPN/OpenConnect exit whose tunnel is up.
  int urlTest();

  // Refreshes the running instance's remote rule-sets; blocks up to about 60 s. Returns {"updated":n,"error":"text"}.
  String updateRuleSets();

  // The running auto-selector (bg.autoselector.AutoSelectorStatus as JSON; the member table only withMembers, which
  // cbAutoSelectorUpdate never carries); re-probe every running member; pin a member profile (-1 = automatic). Both
  // actions return at once and report through cbAutoSelectorUpdate.
  String autoSelectorStatus(boolean withMembers);
  void autoSelectorRecheck();
  void autoSelectorSelect(long memberProfileId);

  // The live graphs' points after afterSerial of session (bg.LiveStatsRecorder); another session returns them all.
  LiveStatsBatch liveStats(long session, long afterSerial);

  // OpenVPN/OpenConnect sign-in of the running instance (bg.VpnAuthRuntime): its pending challenges and refused
  // sign-ins as bg.VpnAuthState JSON, also pushed through cbVpnAuthUpdate. Submit and cancel return "" or the core's
  // error; answerJson is {"username","password","secret","form_values":{submission_key: value}} and is never logged.
  String vpnAuthState();
  String submitVpnChallenge(String tag, String id, String answerJson);
  String cancelVpnChallenge(String tag, String id);
}
