import * from whiley.lang.*

void f([int] ls) requires no { i in {-1,0,1,2,3} | i >= 0 && i < |ls| && ls[i] < 0}:
    debug str(ls)

void g([int] ls) requires |ls| > 0:
    f(ls)

void ::main(System sys,[string] args):
    g([-1,1,2,3])
