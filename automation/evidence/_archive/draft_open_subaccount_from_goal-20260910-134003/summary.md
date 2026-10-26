# Replay Summary: draft_open_subaccount_from_goal

- Status: `failure`
- Checkpoint: `none`
- Outcome: `none`
- Failure: `BrowserType.launch: Target page, context or browser has been closed
Browser logs:

<launching> /Users/harshitpotluri/mock-bank/automation/.playwright-browsers/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell --disable-field-trial-config --disable-background-networking --disable-background-timer-throttling --disable-backgrounding-occluded-windows --disable-back-forward-cache --disable-breakpad --disable-client-side-phishing-detection --disable-component-extensions-with-background-pages --disable-component-update --no-default-browser-check --disable-default-apps --disable-dev-shm-usage --disable-edgeupdater --disable-extensions --disable-features=AvoidUnnecessaryBeforeUnloadCheckSync,BoundaryEventDispatchTracksNodeRemoval,DestroyProfileOnBrowserClose,DialMediaRouteProvider,GlobalMediaControls,HttpsUpgrades,LensOverlay,MediaRouter,PaintHolding,ThirdPartyStoragePartitioning,BlockOriginHeaderModificationOnRedirect,Translate,AutoDeElevate,OptimizationHints,msForceBrowserSignIn,msEdgeUpdateLaunchServicesPreferredVersion --enable-features=CDPScreenshotNewSurface --allow-pre-commit-input --disable-hang-monitor --disable-ipc-flooding-protection --disable-popup-blocking --disable-prompt-on-repost --disable-renderer-backgrounding --disable-updater-scheduler --force-color-profile=srgb --metrics-recording-only --no-first-run --password-store=basic --use-mock-keychain --no-service-autorun --export-tagged-pdf --disable-search-engine-choice-screen --unsafely-disable-devtools-self-xss-warnings --edge-skip-compat-layer-relaunch --disable-infobars --disable-search-engine-choice-screen --disable-sync --enable-unsafe-swiftshader --headless --hide-scrollbars --mute-audio --blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4 --no-sandbox --user-data-dir=/var/folders/2d/wzdsx28j42bcc5_0pt8znph80000gn/T/playwright_chromiumdev_profile-j3fZrW --remote-debugging-pipe --no-startup-window
<launched> pid=27854
[pid=27854][err] [0910/134004.181122:ERROR:base/power_monitor/thermal_state_observer_mac.mm:140] ThermalStateObserverMac unable to register to power notifications. Result: 9
[pid=27854][err] [0910/134004.201665:ERROR:net/dns/dns_config_service_posix.cc:138] DNS config watch failed to start.
[pid=27854][err] [0910/134004.203531:WARNING:net/dns/dns_config_service_posix.cc:197] Failed to read DnsConfig.
[pid=27854][err] [0910/134004.210819:FATAL:base/apple/mach_port_rendezvous_mac.cc:159] Check failed: kr == KERN_SUCCESS. bootstrap_check_in org.chromium.Chromium.MachPortRendezvousServer.27854: Permission denied (1100)
Call log:
  - <launching> /Users/harshitpotluri/mock-bank/automation/.playwright-browsers/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell --disable-field-trial-config --disable-background-networking --disable-background-timer-throttling --disable-backgrounding-occluded-windows --disable-back-forward-cache --disable-breakpad --disable-client-side-phishing-detection --disable-component-extensions-with-background-pages --disable-component-update --no-default-browser-check --disable-default-apps --disable-dev-shm-usage --disable-edgeupdater --disable-extensions --disable-features=AvoidUnnecessaryBeforeUnloadCheckSync,BoundaryEventDispatchTracksNodeRemoval,DestroyProfileOnBrowserClose,DialMediaRouteProvider,GlobalMediaControls,HttpsUpgrades,LensOverlay,MediaRouter,PaintHolding,ThirdPartyStoragePartitioning,BlockOriginHeaderModificationOnRedirect,Translate,AutoDeElevate,OptimizationHints,msForceBrowserSignIn,msEdgeUpdateLaunchServicesPreferredVersion --enable-features=CDPScreenshotNewSurface --allow-pre-commit-input --disable-hang-monitor --disable-ipc-flooding-protection --disable-popup-blocking --disable-prompt-on-repost --disable-renderer-backgrounding --disable-updater-scheduler --force-color-profile=srgb --metrics-recording-only --no-first-run --password-store=basic --use-mock-keychain --no-service-autorun --export-tagged-pdf --disable-search-engine-choice-screen --unsafely-disable-devtools-self-xss-warnings --edge-skip-compat-layer-relaunch --disable-infobars --disable-search-engine-choice-screen --disable-sync --enable-unsafe-swiftshader --headless --hide-scrollbars --mute-audio --blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4 --no-sandbox --user-data-dir=/var/folders/2d/wzdsx28j42bcc5_0pt8znph80000gn/T/playwright_chromiumdev_profile-j3fZrW --remote-debugging-pipe --no-startup-window
  - <launched> pid=27854
  - [pid=27854][err] [0910/134004.181122:ERROR:base/power_monitor/thermal_state_observer_mac.mm:140] ThermalStateObserverMac unable to register to power notifications. Result: 9
  - [pid=27854][err] [0910/134004.201665:ERROR:net/dns/dns_config_service_posix.cc:138] DNS config watch failed to start.
  - [pid=27854][err] [0910/134004.203531:WARNING:net/dns/dns_config_service_posix.cc:197] Failed to read DnsConfig.
  - [pid=27854][err] [0910/134004.210819:FATAL:base/apple/mach_port_rendezvous_mac.cc:159] Check failed: kr == KERN_SUCCESS. bootstrap_check_in org.chromium.Chromium.MachPortRendezvousServer.27854: Permission denied (1100)
  - [pid=27854] <gracefully close start>
  - [pid=27854] <kill>
  - [pid=27854] <will force kill>
  - [pid=27854] exception while trying to kill process: Error: kill EPERM
  - [pid=27854] <process did exit: exitCode=null, signal=SIGTRAP>
  - [pid=27854] starting temporary directories cleanup
  - [pid=27854] finished temporary directories cleanup
  - [pid=27854] <gracefully close end>
`
- Steps completed: `0`

## Outputs
- none

## Evidence
- `artifact_snapshot`: `evidence/draft_open_subaccount_from_goal-20260910-134003/artifact.redacted.json`
- `redacted_inputs`: `evidence/draft_open_subaccount_from_goal-20260910-134003/inputs.redacted.json`
- `result`: `evidence/draft_open_subaccount_from_goal-20260910-134003/result.json`
- `run_dir`: `evidence/draft_open_subaccount_from_goal-20260910-134003`
- `summary`: `evidence/draft_open_subaccount_from_goal-20260910-134003/summary.md`
