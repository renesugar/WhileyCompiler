// this is a comment!
define int|[int] as IntList

void System::f(int y):
    print str(y)

void System::g([int] z):
    print str(z)

void System::main([string] args):
    IntList x = 123
    this->f(x)
    x = [1,2,3]
    this->g(x)
