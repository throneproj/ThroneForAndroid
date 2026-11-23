package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.SpeedDisplayData;
import io.nekohasekai.sagernet.aidl.TrafficDataBatch;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficDataBatch stats);
  void cbSelectorUpdate(long id);
  void cbAutoSelectorUpdate(String json);
  void cbVpnAuthUpdate(String json);
}
