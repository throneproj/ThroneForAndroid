package io.nekohasekai.sagernet.aidl;

// IP scanner runs (ScanEngine), delivered in order: onSync first after registering, with a JSON array of every run's
// state; then onState with a run's state JSON on every change (progress at most every 250 ms) and onResults when a
// run's results changed (a saved scan's result list, a finder's hits).
oneway interface IScanCallback {
  void onSync(String statesJson);
  void onState(long id, String stateJson);
  void onResults(long id);
}
