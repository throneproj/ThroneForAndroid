package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.bg.test.ChainStepReport;

// The verdicts of ICoreService.startChainStepTest, delivered once when the test is over (stopped included).
oneway interface IChainStepCallback {
  void onDone(in ChainStepReport report);
}
