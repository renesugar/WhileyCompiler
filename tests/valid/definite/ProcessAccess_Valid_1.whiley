define (int mode, int rest) as etype
define process etype as Ptype

int Ptype::get():
    this->mode = 1
    this->rest = 123
    print str(*this)   
    return this->mode

void System::main([string] args):
    Ptype p = spawn (mode:1,rest:2)
    print str(*p)
    int x = p->get()
    print str(*p)
    print str(x)
