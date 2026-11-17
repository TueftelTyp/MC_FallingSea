from pathlib import Path
s=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'VERIFICATION_DELAY_TICKS = 10' in s
assert 'verificationRemaining=cfg.suppressInfiniteSources' in s
assert 'startVerificationPass(server,cfg,target);' in s
assert 'if(!verificationPass && !work.elevatedSweep' in s
assert 'if(!verificationPass && !work.flowCleanup' in s
assert 'settlingTicks==0 && verificationRemaining==0' not in s
assert 'return scannedLevel==target && surfaceQueue.isEmpty();' in s
assert 'passCompletionRecorded=true;' in s
assert 'if(!candidates.isEmpty()) passCompletionRecorded=false;' in s
print('Water-mound reconciliation contracts passed.')
