define (int f1, int f2) where f1 < f2 as tac1tup

void System::main([string] args):
    tac1tup x = (f1:1,f2:3)
    x.f1 = 2
    assert x.f1 == x.f2
    print str(x)
