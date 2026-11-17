"""Model test for bounded, adaptive water-mound reconciliation."""
from pathlib import Path
src=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'MAX_VERIFICATION_PASSES = 8' in src
assert 'removedAtVerificationStart=removedBlocks' in src
assert 'removedBlocks>removedAtVerificationStart' in src
assert 'verificationAttempts<MAX_VERIFICATION_PASSES' in src
assert 'verificationRemaining=Math.max(verificationRemaining,1)' in src
assert 'readyForNextAutomaticStep' in src

def simulate(removals, limit=8):
    attempts=0
    for removed in removals:
        if attempts>=limit:break
        attempts+=1
        if removed==0:break
    return attempts
assert simulate([0]) == 1
assert simulate([5, 2, 0]) == 3
assert simulate([1]*100) == 8
print('Adaptive verification state model passed (clean, late refill, endless refill).')
