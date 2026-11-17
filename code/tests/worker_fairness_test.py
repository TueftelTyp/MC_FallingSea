"""Reference-model scheduling tests, no Minecraft dependencies."""
from collections import deque

def allocate(chunks, quantum, budget):
    queue=deque((name, work) for name,work in chunks)
    removed=[]
    while queue and budget:
        name,work=queue.popleft()
        processed=min(quantum,budget,work)
        removed.append((name,processed))
        budget-=processed
        if work>processed:queue.append((name,work-processed))
    return removed

for count in (2,4,25,49):
    result=allocate([(i,4096) for i in range(count)],256,count*256)
    assert len(result)==count, (count,result)
    assert len({i for i,_ in result})==count
    assert sum(x for _,x in result)==count*256
for budget in (1,255,256,257,4096,5000,20000):
    result=allocate([(i,4096) for i in range(12)],256,budget)
    assert sum(used for _,used in result)==budget
print('Scheduling model passed for 2/4/25/49 chunks and variable budgets.')
