define pos as int where $ > 0
define neg as int where $ < 0

define intlist as pos|neg|[int]

int f(intlist x):
    if x ~= int:
        return x
    return 1 

void System::main([string] args):
    int x = f([1,2,3])
    print str(x)
    x = f(1)
    print str(x)

