int g(int x) ensures $ > 0 && $ < 125:
    if(x <= 0 || x >= 125):
        return 1
    else:
        return x

[byte] f(int x):
    return [g(x)]

void System::main([string] args):
    [byte] bytes = f(0)
    print str(bytes)

