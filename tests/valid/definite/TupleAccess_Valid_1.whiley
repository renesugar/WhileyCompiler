define (int mode, ? rest) as etype
define process etype as Ptype

int Ptype::get():
    this->mode = 1
    return this->mode

void System::main([string] args):
    print "OK"
