// This file is part of the Whiley-to-Java Compiler (wyjc).
//
// The Whiley-to-Java Compiler is free software; you can redistribute 
// it and/or modify it under the terms of the GNU General Public 
// License as published by the Free Software Foundation; either 
// version 3 of the License, or (at your option) any later version.
//
// The Whiley-to-Java Compiler is distributed in the hope that it 
// will be useful, but WITHOUT ANY WARRANTY; without even the 
// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR 
// PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public 
// License along with the Whiley-to-Java Compiler. If not, see 
// <http://www.gnu.org/licenses/>
//
// Copyright 2010, David James Pearce. 

package wyjc.stages;

import java.util.*;
import java.math.BigInteger;

import static wyil.util.SyntaxError.*;
import wyil.ModuleLoader;
import wyil.stages.TypePropagation;
import wyil.util.*;
import wyil.lang.*;
import wyil.lang.Code.IfGoto;
import wyjc.lang.*;
import wyjc.lang.WhileyFile.*;
import wyjc.lang.Stmt;
import wyjc.lang.Stmt.*;
import wyjc.lang.Expr.*;

public class ModuleBuilder {
	private final ModuleLoader loader;	
	private HashSet<ModuleID> modules;
	private HashMap<NameID, WhileyFile> filemap;
	private HashMap<NameID, List<Type.Fun>> functions;
	private HashMap<NameID, Pair<Type, Block>> types;
	private HashMap<NameID, Value> constants;
	private HashMap<NameID, Pair<UnresolvedType, Expr>> unresolved;
	private String filename;
	private FunDecl currentFunDecl;

	// The shadow set is used to (efficiently) aid the correct generation of
	// runtime checks for post conditions. The key issue is that a post
	// condition may refer to parameters of the method. However, if those
	// parameters are modified during the method, then we must store their
	// original value on entry for use in the post-condition runtime check.
	// These stored values are called "shadows".
	private final HashMap<String, CExpr> shadows = new HashMap<String, CExpr>();

	public ModuleBuilder(ModuleLoader loader) {
		this.loader = loader;		
	}

	public List<Module> resolve(List<WhileyFile> files) {
		modules = new HashSet<ModuleID>();
		filemap = new HashMap<NameID, WhileyFile>();
		functions = new HashMap<NameID, List<Type.Fun>>();
		types = new HashMap<NameID, Pair<Type, Block>>();
		constants = new HashMap<NameID, Value>();
		unresolved = new HashMap<NameID, Pair<UnresolvedType, Expr>>();

		// now, init data
		for (WhileyFile f : files) {
			modules.add(f.module);
		}

		// Stage 1 ... resolve and check types of all named types + constants
		generateConstants(files);
		generateTypes(files);

		// Stage 2 ... resolve and check types for all functions / methods
		for (WhileyFile f : files) {
			for (WhileyFile.Decl d : f.declarations) {
				if (d instanceof FunDecl) {
					partResolve(f.module, (FunDecl) d);
				}
			}
		}

		// Stage 3 ... resolve, propagate types for all expressions
		ArrayList<Module> modules = new ArrayList<Module>();
		for (WhileyFile f : files) {
			modules.add(resolve(f));
		}

		return modules;
	}

	public Module resolve(WhileyFile wf) {
		this.filename = wf.filename;
		HashMap<Pair<Type.Fun, String>, Module.Method> methods = new HashMap();
		ArrayList<Module.TypeDef> types = new ArrayList<Module.TypeDef>();
		ArrayList<Module.ConstDef> constants = new ArrayList<Module.ConstDef>();
		for (WhileyFile.Decl d : wf.declarations) {
			try {
				if (d instanceof TypeDecl) {
					types.add(resolve((TypeDecl) d, wf.module));
				} else if (d instanceof ConstDecl) {
					constants.add(resolve((ConstDecl) d, wf.module));
				} else if (d instanceof FunDecl) {
					Module.Method mi = resolve((FunDecl) d);
					Pair<Type.Fun, String> key = new Pair(mi.type(), mi.name());
					Module.Method method = methods.get(key);
					if (method != null) {
						// coalesce cases
						ArrayList<Module.Case> ncases = new ArrayList<Module.Case>(
								method.cases());
						ncases.addAll(mi.cases());
						mi = new Module.Method(mi.name(), mi.type(), ncases);
					}
					methods.put(key, mi);
				}
			} catch (SyntaxError se) {
				throw se;
			} catch (Throwable ex) {
				syntaxError("internal failure", wf.filename, d, ex);
			}
		}
		
		return new Module(wf.module, wf.filename, methods.values(), types,
				constants);				
	}

	/**
	 * The following method visits every define constant statement in every
	 * whiley file being compiled, and determines its true and value.
	 * 
	 * @param files
	 */
	protected void generateConstants(List<WhileyFile> files) {
		HashMap<NameID, Expr> exprs = new HashMap();

		// first construct list.
		for (WhileyFile f : files) {
			for (Decl d : f.declarations) {
				if (d instanceof ConstDecl) {
					ConstDecl cd = (ConstDecl) d;
					NameID key = new NameID(f.module, cd.name());
					exprs.put(key, cd.constant);
					filemap.put(key, f);
				}
			}
		}

		for (NameID k : exprs.keySet()) {
			try {
				Value v = expandConstant(k, exprs, new HashSet<NameID>());
				constants.put(k, v);
				Type t = v.type();
				if (t instanceof Type.Set) {
					Type.Set st = (Type.Set) t;
					Block block = new Block();
					String label = Block.freshLabel();
					CExpr var = CExpr.VAR(st.element, "$");
					Attribute attr = exprs.get(k).attribute(
							Attribute.Source.class);
					block.add(new Code.IfGoto(Code.COP.ELEMOF, var, v, label),
							attr);
					block.add(new Code.Fail("type constraint not satisfied"),
							attr);
					block.add(new Code.Label(label));
					types.put(k, new Pair<Type, Block>(st.element, block));
				}
			} catch (ResolveError rex) {
				syntaxError(rex.getMessage(), filemap.get(k).filename, exprs
						.get(k), rex);
			}
		}
	}

	/**
	 * The expand constant method is responsible for turning a named constant
	 * expression into a value. This is done by traversing the constant's
	 * expression and recursively expanding any named constants it contains.
	 * Simplification of constants is also performed where possible.
	 * 
	 * @param key
	 *            --- name of constant we are expanding.
	 * @param exprs
	 *            --- mapping of all names to their( declared) expressions
	 * @param visited
	 *            --- set of all constants seen during this traversal (used to
	 *            detect cycles).
	 * @return
	 * @throws ResolveError
	 */
	protected Value expandConstant(NameID key, HashMap<NameID, Expr> exprs,
			HashSet<NameID> visited) throws ResolveError {
		Expr e = exprs.get(key);
		Value value = constants.get(key);
		if (value != null) {
			return value;
		} else if (!modules.contains(key.module())) {
			// indicates a non-local key
			Module mi = loader.loadModule(key.module());
			return mi.constant(key.name()).constant();
		} else if (visited.contains(key)) {
			// this indicates a cyclic definition.
			syntaxError("cyclic constant definition encountered", filemap
					.get(key).filename, exprs.get(key));
		} else {
			visited.add(key); // mark this node as visited
		}

		// At this point, we need to replace every unresolved variable with a
		// constant definition.
		Value v = expandConstantHelper(e, filemap.get(key).filename, exprs,
				visited);
		constants.put(key, v);
		return v;
	}

	/**
	 * The following is a helper method for expandConstant. It takes a given
	 * expression (rather than the name of a constant) and expands to a value
	 * (where possible). If the expression contains, for example, method or
	 * function declarations then this will certainly fail (producing a syntax
	 * error).
	 * 
	 * @param key
	 *            --- name of constant we are expanding.
	 * @param exprs
	 *            --- mapping of all names to their( declared) expressions
	 * @param visited
	 *            --- set of all constants seen during this traversal (used to
	 *            detect cycles).
	 */
	protected Value expandConstantHelper(Expr expr, String filename,
			HashMap<NameID, Expr> exprs, HashSet<NameID> visited)
			throws ResolveError {
		if (expr instanceof Constant) {
			Constant c = (Constant) expr;
			return c.value;
		} else if (expr instanceof Variable) {
			// Note, this must be a constant definition of some sort
			Variable v = (Variable) expr;
			Attributes.Module mid = expr.attribute(Attributes.Module.class);
			if (mid != null) {
				NameID name = new NameID(mid.module, v.var);
				return expandConstant(name, exprs, visited);
			}
		} else if (expr instanceof BinOp) {
			BinOp bop = (BinOp) expr;
			Value lhs = expandConstantHelper(bop.lhs, filename, exprs, visited);
			Value rhs = expandConstantHelper(bop.rhs, filename, exprs, visited);
			Value v = Value.evaluate(OP2BOP(bop.op, expr), lhs, rhs);
			if (v != null) {
				return v;
			}
		} else if (expr instanceof NaryOp) {
			Expr.NaryOp nop = (NaryOp) expr;
			ArrayList<Value> values = new ArrayList<Value>();
			for (Expr arg : nop.arguments) {
				values.add(expandConstantHelper(arg, filename, exprs, visited));
			}
			if (nop.nop == Expr.NOp.LISTGEN) {
				return Value.V_LIST(values);
			} else if (nop.nop == Expr.NOp.SETGEN) {
				return Value.V_SET(values);
			}
		}

		syntaxError("invalid expression in constant definition", filename, expr);
		return null;
	}

	/**
	 * The following method visits every define type statement in every whiley
	 * file being compiled, and determines its true type.
	 * 
	 * @param files
	 */
	protected void generateTypes(List<WhileyFile> files) {
		HashMap<NameID, SyntacticElement> srcs = new HashMap();

		// second construct list.
		for (WhileyFile f : files) {
			for (Decl d : f.declarations) {
				if (d instanceof TypeDecl) {
					TypeDecl td = (TypeDecl) d;					
					NameID key = new NameID(f.module, td.name());					
					unresolved.put(key, new Pair<UnresolvedType, Expr>(td.type,
							td.constraint));
					srcs.put(key, d);
					filemap.put(key, f);
				}
			}
		}

		// third expand all types
		for (NameID key : unresolved.keySet()) {			
			try {
				HashMap<NameID, Type> cache = new HashMap<NameID, Type>();				
				Pair<Type, Block> p = expandType(key, cache);
				// p = simplifyRecursiveTypes(key.toString(),p); // probably doesn't make sense
				Type t = Type.nameType(new NameID(key.module(), key.name()), p.first());
				Block blk = p.second();							
				types.put(key, new Pair<Type, Block>(t, blk));
			} catch (ResolveError ex) {
				syntaxError(ex.getMessage(), filemap.get(key).filename, srcs
						.get(key), ex);
			}
		}
	}

	protected Pair<Type, Block> expandType(NameID key,
			HashMap<NameID, Type> cache) throws ResolveError {
		
		Type cached = cache.get(key);
		Pair<Type, Block> t = types.get(key);
		
		if (cached != null) {
			Block blk = null;
			/* FIXME: Following needs to be updated after move away from recursives
			if(cached instanceof Type.Recursive) {
				Type.Recursive r = (Type.Recursive) cached;				
				if(r.type == null) {
					// need to put in place the recursive call.
					blk = new Block();
					blk.add(new Code.Recurse(CExpr.VAR(Type.T_ANY,"$")));
				}
			}
			
			*/
			return new Pair<Type, Block>(cached, blk);
		} else if(t != null) {
			return new Pair<Type, Block>(t.first(), Block.relabel(t.second()));
		} else if (!modules.contains(key.module())) {
			// indicates a non-local key which we can resolve immediately
			Module mi = loader.loadModule(key.module());
			Module.TypeDef td = mi.type(key.name());
			return new Pair<Type, Block>(td.type(), Block.relabel(td
					.constraint()));
		}

		// following is needed to terminate any recursion
		cache.put(key, Type.T_RECURSE(key));

		// Ok, expand the type properly then
		Pair<UnresolvedType, Expr> ut = unresolved.get(key);
		t = expandType(ut.first(), filemap.get(key).filename,
				cache);

		// Now, we need to test whether the current type is open and recursive
		// on this name. In such case, we must close it in order to complete the
		// recursive type.
		boolean isOpenRecursive = Type.isOpenRecursive(key, t.first());
		
		// Resolve the constraint and generate an appropriate block.
		Block blk = t.second();		
		if (ut.second() != null) {
			String trueLabel = Block.freshLabel();
			Block constraint = resolveCondition(trueLabel, ut.second(), 0);
			constraint.add(new Code.Fail("type constraint not satisfied"), ut
					.second().attribute(Attribute.Source.class));
			constraint.add(new Code.Label(trueLabel));

			if (blk == null) { 
				t = new Pair<Type, Block>(t.first(), constraint);
			} else {
				blk.addAll(constraint); // affects t
			}
		}
		
		if(t.second() != null && isOpenRecursive) {
			// For the case of a recursive type, we need to create an inductive
			// block which traverses the recursive structure checking any
			// constraints as necessary.
			String lab = Block.freshLabel();
			CExpr src = CExpr.VAR(Type.T_ANY, "$");
			CExpr.Register var = CExpr.REG(Type.T_ANY,0);			
			blk = Block.registerShift(1,t.second());

			// finally, create the inductive block
			blk.add(0,new Code.Induct(lab, var, src));
			blk.add(new Code.InductEnd(lab));
			t = new Pair<Type, Block>(t.first(), blk);			
		}				
		
		// finally, store it in the cache
		cache.put(key, t.first());

		// Done
		return t;
	}

	protected Pair<Type, Block> expandType(UnresolvedType t, String filename,
			HashMap<NameID, Type> cache) {
		if (t instanceof UnresolvedType.List) {
			UnresolvedType.List lt = (UnresolvedType.List) t;
			Pair<Type, Block> p = expandType(lt.element, filename, cache);
			Type rt = Type.T_LIST(p.first());
			String label = Block.freshLabel();
			Block blk = null;
			if (p.second() != null) {
				blk = new Block();
				CExpr.Register reg = CExpr.REG(Type.T_ANY, 0);
				// FIXME: need some line number information here?
				blk.add(new Code.Forall(label, null, reg, CExpr.VAR(Type.T_ANY, "$")));
				blk.addAll(Block.substitute("$", reg, Block.registerShift(1, p
						.second())));
				blk.add(new Code.ForallEnd(label));
			}
			return new Pair<Type, Block>(rt, blk);
		} else if (t instanceof UnresolvedType.Set) {
			UnresolvedType.Set st = (UnresolvedType.Set) t;
			Pair<Type, Block> p = expandType(st.element, filename, cache);
			Type rt = Type.T_SET(p.first());
			String label = Block.freshLabel();
			Block blk = null;
			if (p.second() != null) {
				blk = new Block();
				CExpr.Register reg = CExpr.REG(Type.T_ANY, 0);
				// FIXME: need some line number information here?
				blk.add(new Code.Forall(label, null, reg, CExpr.VAR(Type.T_ANY,
						"$")));
				blk.addAll(Block.substitute("$", reg, Block.registerShift(1, p
						.second())));
				blk.add(new Code.ForallEnd(label));
			}
			return new Pair<Type, Block>(rt, blk);
		} else if (t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			HashMap<String, Type> types = new HashMap<String, Type>();
			Block blk = null;
			CExpr.Variable tmp = CExpr.VAR(Type.T_ANY, "$");
			for (Map.Entry<String, UnresolvedType> e : tt.types.entrySet()) {
				Pair<Type, Block> p = expandType(e.getValue(), filename, cache);
				types.put(e.getKey(), p.first());
				if (p.second() != null) {
					if (blk == null) {
						blk = new Block();
					}
					HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
					binding.put("$", CExpr.RECORDACCESS(tmp, e.getKey()));
					blk.addAll(Block.substitute(binding, p.second()));
				}
			}
			Type type = Type.T_RECORD(types);
			// Need to update the self type properly
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$", CExpr.VAR(Type.T_ANY, "$"));
			return new Pair<Type, Block>(type, Block.substitute(binding, blk));
		} else if (t instanceof UnresolvedType.Union) {
			UnresolvedType.Union ut = (UnresolvedType.Union) t;
			HashSet<Type.NonUnion> bounds = new HashSet<Type.NonUnion>();
			Block blk = new Block();
			String exitLabel = Block.freshLabel();
			CExpr.Variable var = CExpr.VAR(Type.T_ANY, "$#");
			for (UnresolvedType b : ut.bounds) {			
				Pair<Type, Block> p = expandType(b, filename, cache);
				Type bt = p.first();
				if (bt instanceof Type.NonUnion) {
					bounds.add((Type.NonUnion) bt);
				} else {
					bounds.addAll(((Type.Union) bt).bounds);
				}
												
				if(p.second() != null) {
					String nextLabel = Block.freshLabel();
					blk.add(new Code.IfGoto(Code.COP.NSUBTYPEEQ, var, Value
						.V_TYPE(p.first()), nextLabel));				
					blk.addAll(Block.chain(nextLabel, p.second()));				
					blk.add(new Code.Goto(exitLabel));
					blk.add(new Code.Label(nextLabel));
				} else {
					blk.add(new Code.IfGoto(Code.COP.SUBTYPEEQ, var, Value
							.V_TYPE(p.first()), exitLabel));						
				}
			}
			// FIXME: need some line number information here
			blk.add(new Code.Fail("type constraint not satisfied"));
			blk.add(new Code.Label(exitLabel));
			
			Type type;
			if (bounds.size() == 1) {
				type = bounds.iterator().next();
			} else {
				type = Type.leastUpperBound(bounds);
			}
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$#", CExpr.VAR(Type.T_ANY, "$"));
			return new Pair<Type, Block>(type, Block.substitute(binding, blk));
		} else if (t instanceof UnresolvedType.Process) {
			UnresolvedType.Process ut = (UnresolvedType.Process) t;
			Pair<Type, Block> p = expandType(ut.element, filename, cache);			
			Type type = Type.T_PROCESS(p.first());
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$", CExpr.UNOP(CExpr.UOP.PROCESSACCESS, CExpr.VAR(Type.T_ANY, "$")));			
			return new Pair<Type, Block>(type, Block.substitute(binding, p.second()));					
		} else if (t instanceof UnresolvedType.Named) {
			UnresolvedType.Named dt = (UnresolvedType.Named) t;
			Attributes.Module modInfo = dt.attribute(Attributes.Module.class);
			NameID name = new NameID(modInfo.module, dt.name);

			try {
				Pair<Type, Block> et = expandType(name, cache);
				return new Pair<Type, Block>(Type.nameType(new NameID(modInfo.module,
							dt.name), et.first()), et.second());				
			} catch (ResolveError rex) {
				syntaxError(rex.getMessage(), filename, t, rex);
				return null;
			}
		} else {
			// for base cases
			return resolve(t);
		}
	}

	protected void partResolve(ModuleID module, FunDecl fd) {

		ArrayList<Type> parameters = new ArrayList<Type>();
		for (WhileyFile.Parameter p : fd.parameters) {
			parameters.add(resolve(p.type).first());
		}

		// method return type
		Type ret = resolve(fd.ret).first();

		// method receiver type (if applicable)
		Type.Process rec = null;
		if (fd.receiver != null) {
			Type t = resolve(fd.receiver).first();
			checkType(t, Type.Process.class, fd.receiver);
			rec = (Type.Process) t;
		}

		Type.Fun ft = Type.T_FUN(rec, ret, parameters);
		NameID name = new NameID(module, fd.name);
		List<Type.Fun> types = functions.get(name);
		if (types == null) {
			types = new ArrayList<Type.Fun>();
			functions.put(name, types);
		}
		types.add(ft);
		fd.attributes().add(new Attributes.Fun(ft));
	}

	protected Module.ConstDef resolve(ConstDecl td, ModuleID module) {
		Value v = constants.get(new NameID(module, td.name()));
		return new Module.ConstDef(td.name(), v);
	}

	protected Module.TypeDef resolve(TypeDecl td, ModuleID module) {
		Pair<Type, Block> p = types.get(new NameID(module, td.name()));			
		return new Module.TypeDef(td.name(), p.first(), p.second());
	}

	protected Module.Method resolve(FunDecl fd) {
		ArrayList<String> parameterNames = new ArrayList<String>();
		Block precondition = null;

		// method parameter types
		for (WhileyFile.Parameter p : fd.parameters) {
			Pair<Type, Block> t = resolve(p.type);		
			Block constraint = t.second();
			if(constraint != null) {
				HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
				binding.put("$", CExpr.VAR(Type.T_ANY, p.name));
				constraint = Block.substitute(binding, constraint);
				t = new Pair<Type,Block>(t.first(),constraint);
			}
						
			parameterNames.add(p.name());
			if (t.second() != null) {
				if (precondition == null) {
					precondition = new Block();
				}
				precondition.addAll(constraint);
			}
		}

		// method return type
		Pair<Type, Block> ret = resolve(fd.ret);
		Block postcondition = ret.second();

		// method receiver type (if applicable)
		if (fd.receiver != null) {
			Pair<Type, Block> rec = resolve(fd.receiver);					
		}

		if (fd.precondition != null) {
			String trueLabel = Block.freshLabel();
			Block tmp = resolveCondition(trueLabel, fd.precondition, 0);
			tmp.add(new Code.Fail("function precondition not satisfied"),
					fd.precondition.attribute(Attribute.Source.class));
			tmp.add(new Code.Label(trueLabel));
			if (precondition == null) {
				precondition = tmp;
			} else {
				precondition.addAll(tmp);
			}
		}

		if (fd.postcondition != null) {				
			String trueLabel = Block.freshLabel();			
			Attribute.Source attr = fd.postcondition.attribute(Attribute.Source.class);			
			Block tmp = resolveCondition(trueLabel, fd.postcondition, 0);
			tmp.add(new Code.Fail("function postcondition not satisfied"),
					attr);
			tmp.add(new Code.Label(trueLabel));						
			if (postcondition == null) {
				postcondition = tmp;
			} else {
				postcondition.addAll(tmp);
			}
		}

		currentFunDecl = fd;
		Type.Fun tf = fd.attribute(Attributes.Fun.class).type;

		Block blk = new Block();

		// free reg determines the first free register.
		int freeReg = determineShadows(postcondition, parameterNames, tf, blk);
		for (Stmt s : fd.statements) {
			blk.addAll(resolve(s, freeReg));
		}

		currentFunDecl = null;
		
		// The following is sneaky. It guarantees that every method ends in a
		// return. For methods that actually need a value, this is either
		// removed as dead-code or remains and will cause an error.
		blk.add(new Code.Return(null),fd.attribute(Attribute.Source.class));

		Module.Case ncase = new Module.Case(parameterNames, precondition,
				postcondition, blk);
		return new Module.Method(fd.name(), tf, ncase);
	}

	/**
	 * Determine which parameters require shadows. A parameter requires a shadow
	 * if: it is used in the post-condition: and, it is modified in the method
	 * body.
	 * 
	 * @param f
	 */
	protected int determineShadows(Block postcondition,
			List<String> parameterNames, Type.Fun ft, Block body) {
		shadows.clear();
		int freeReg = 0;
		if (postcondition != null) {
			HashMap<String, Type> binding = new HashMap<String, Type>();
			for (int i = 0; i != parameterNames.size(); ++i) {
				String name = parameterNames.get(i);
				Type t = ft.params.get(i);
				binding.put(name, t);
			}

			HashSet<CExpr.Variable> uses = new HashSet<CExpr.Variable>();
			Block.match(postcondition, CExpr.Variable.class, uses);

			for (CExpr.Variable v : uses) {
				if (!v.name.equals("$")) {
					CExpr.LVar shadow = CExpr.REG(binding.get(v.name),
							freeReg++);
					shadows.put(v.name, shadow);
					Code code = new Code.Assign(shadow, CExpr.VAR(binding
							.get(v.name), v.name));
					body.add(code);
				}
			}
		}
		return freeReg;
	}

	public Block resolve(Stmt stmt, int freeReg) {
		try {
			if (stmt instanceof Assign) {
				return resolve((Assign) stmt, freeReg);
			} else if (stmt instanceof Assert) {
				return resolve((Assert) stmt, freeReg);
			} else if (stmt instanceof Return) {
				return resolve((Return) stmt, freeReg);
			} else if (stmt instanceof Debug) {
				return resolve((Debug) stmt, freeReg);
			} else if (stmt instanceof IfElse) {
				return resolve((IfElse) stmt, freeReg);
			} else if (stmt instanceof While) {
				return resolve((While) stmt, freeReg);
			} else if (stmt instanceof For) {
				return resolve((For) stmt, freeReg);
			} else if (stmt instanceof Invoke) {
				Pair<CExpr, Block> p = resolve(freeReg, (Invoke) stmt);
				Block blk = p.second();
				blk.add(new Code.Assign(null, p.first()), stmt
						.attribute(Attribute.Source.class));				
				return blk;
			} else if (stmt instanceof Spawn) {
				return resolve(freeReg, (UnOp) stmt).second();
			} else if (stmt instanceof ExternJvm) {
				return resolve((ExternJvm) stmt, freeReg);
			} else if (stmt instanceof Skip) {
				return resolve((Skip) stmt, freeReg);
			} else {
				syntaxError("unknown statement encountered: "
						+ stmt.getClass().getName(), filename, stmt);
			}
		} catch (ResolveError rex) {
			syntaxError(rex.getMessage(), filename, stmt, rex);
		} catch (SyntaxError sex) {
			throw sex;
		} catch (Exception ex) {			
			syntaxError("internal failure", filename, stmt, ex);
		}
		return null;
	}
	
	protected Block resolve(Assign s, int freeReg) {

		Block blk = new Block();		
		Pair<CExpr, Block> rhs_tb = resolve(freeReg + 1, s.rhs);
		
		if(s.lhs instanceof TupleGen) {
			// this indicates a tuple assignment which must be treated specially.
			TupleGen tg = (TupleGen) s.lhs;			
			CExpr.Register reg = CExpr.REG(Type.T_ANY, freeReg);
			blk.addAll(rhs_tb.second());
			blk.add(new Code.Assign(reg, rhs_tb.first()), s
					.attribute(Attribute.Source.class));
			int idx=0;
			for(Expr e : tg.fields) {
				if(!(e instanceof Variable)) {
					syntaxError("variable expected",filename,e);
				}
				Variable v = (Variable) e;
				blk.add(new Code.Assign(CExpr.VAR(Type.T_ANY, v.var), CExpr
						.RECORDACCESS(reg, "$" + idx++)), e
						.attribute(Attribute.Source.class));					
			}
			return blk;
		}
		
		if(s.lhs instanceof Variable) {
			// This is a special case, needed to prevent field inference from
			// thinking it's a field.
			Variable v = (Variable) s.lhs;
			blk.addAll(rhs_tb.second());
			blk.add(new Code.Assign(CExpr.VAR(Type.T_ANY,v.var), rhs_tb
					.first()), s.attribute(Attribute.Source.class));			
		} else {

			Pair<CExpr, Block> lhs_tb = resolve(freeReg, s.lhs);

			if(lhs_tb.first() instanceof CExpr.LVal) {

				blk.addAll(lhs_tb.second());
				blk.addAll(rhs_tb.second());
				blk.add(new Code.Assign(((CExpr.LVal)lhs_tb.first()), rhs_tb
						.first()), s.attribute(Attribute.Source.class));				
			} else {
				syntaxError("invalid assignment", filename, s);
			}
		}
		
		return blk;
	}

	protected Block resolve(Assert s, int freeReg) {
		String lab = Block.freshLabel();
		String clab = Block.freshLabel();
		Block blk = new Block();
		blk.add(new Code.Check(clab),s.attribute(Attribute.Source.class));
		blk.addAll(resolveCondition(lab, s.expr, freeReg));		
		blk.add(new Code.Fail("assertion failed"), s
				.attribute(Attribute.Source.class));
		blk.add(new Code.Label(lab));
		blk.add(new Code.CheckEnd(clab),s.attribute(Attribute.Source.class));		
		return blk;
	}

	protected Block resolve(Return s, int freeReg) {

		if (s.expr != null) {
			Pair<CExpr, Block> t = resolve(freeReg, s.expr);
			Block blk = new Block();
			blk.addAll(t.second());

			Pair<Type, Block> ret = resolve(currentFunDecl.ret);
			
			Block postcondition = ret.second();
						
			if (currentFunDecl.postcondition != null) {
				
				// first, construct the postcondition block
				String trueLabel = Block.freshLabel();				
				if(postcondition == null) {
					postcondition = new Block();
				}				
				postcondition.addAll(resolveCondition(trueLabel, currentFunDecl.postcondition,
						freeReg));
				postcondition.add(new Code.Fail(
						"function postcondition not satisfied"),
						currentFunDecl.postcondition.attribute(Attribute.Source.class));
				postcondition.add(new Code.Label(trueLabel));
			}
			
			if(postcondition != null) {
				// Now, write it into the block
				HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
				CExpr.LVar tmp;
				
				if(t.first() instanceof CExpr.LVar) {
					tmp = (CExpr.LVar) t.first();					
				} else {
					// The following is done to prevent problems with
					// substitution into type test positions.
					tmp = CExpr.REG(Type.T_ANY, freeReg+1);
					blk.add(new Code.Assign(tmp,t.first()),s.attribute(Attribute.Source.class));
					
				}
				binding.put("$", tmp);
				binding.putAll(shadows);
				Block.addCheck(freeReg+2,blk,postcondition,binding,s);
				// now, just return the temporary variable
				blk.add(new Code.Return(tmp), s
						.attribute(Attribute.Source.class));
				return blk;
			} else {
				blk.add(new Code.Return(t.first()), s
						.attribute(Attribute.Source.class));
				return blk;
			}
		} else {
			Block blk = new Block();
			blk.add(new Code.Return(null), s.attribute(Attribute.Source.class));
			return blk;
		}
	}

	protected Block resolve(ExternJvm s, int freeReg) {
		Block blk = new Block();
		blk.add(new Code.ExternJvm(s.bytecodes), s
				.attribute(Attribute.Source.class));
		return blk;
	}

	protected Block resolve(Skip s, int freeReg) {
		Block blk = new Block();
		blk.add(new Code.Skip(), s.attribute(Attribute.Source.class));
		return blk;
	}

	protected Block resolve(Debug s, int freeReg) {
		Pair<CExpr, Block> t = resolve(freeReg, s.expr);
		Block blk = t.second();
		blk.add(new Code.Debug(t.first()), s.attribute(Attribute.Source.class));
		return blk;
	}

	protected Block resolve(IfElse s, int freeReg) {
		String falseLab = Block.freshLabel();
		String exitLab = s.falseBranch.isEmpty() ? falseLab : Block
				.freshLabel();
		Block blk = resolveCondition(falseLab, invert(s.condition), freeReg);

		for (Stmt st : s.trueBranch) {
			blk.addAll(resolve(st, freeReg));
		}
		if (!s.falseBranch.isEmpty()) {
			blk.add(new Code.Goto(exitLab));
			blk.add(new Code.Label(falseLab));
			for (Stmt st : s.falseBranch) {
				blk.addAll(resolve(st, freeReg));
			}
		}

		blk.add(new Code.Label(exitLab));

		return blk;
	}

	protected Block resolve(While s, int freeReg) {		
		String chklab = Block.freshLabel();
		String entry = Block.freshLabel();
		String label = Block.freshLabel();
		String loopend = Block.freshLabel();
		String exitLab = Block.freshLabel();
		
		Block invariant = null;
		Block blk = new Block();
		
		if(s.invariant != null) {
			// FIXME: what I should be doing is loading the invariant into the
			// for, and then inlining it in the PreconditionInline state.
			invariant = new Block();
			invariant.add(new Code.Check(chklab));
			invariant.addAll(resolveCondition(entry, s.invariant, freeReg));
			invariant.add(new Code.Fail("loop invariant not satisfied"), s
					.attribute(Attribute.Source.class));
			invariant.add(new Code.Label(entry));
			invariant.add(new Code.CheckEnd(chklab));
			blk.addAll(Block.relabel(invariant));
		}		
		
		blk.add(new Code.Loop(label, invariant, Collections.EMPTY_SET), s
				.attribute(Attribute.Source.class));
		
		blk.addAll(resolveCondition(exitLab, invert(s.condition), freeReg));

		for (Stmt st : s.body) {
			blk.addAll(resolve(st, freeReg));
		}		
		if(s.invariant != null) {
			blk.add(new Code.Check(chklab));
			blk.addAll(resolveCondition(loopend, s.invariant, freeReg));
			blk.add(new Code.Fail("loop invariant not restored"), s
					.attribute(Attribute.Source.class));
			blk.add(new Code.Label(loopend));
			blk.add(new Code.CheckEnd(chklab));
		}
		blk.add(new Code.LoopEnd(label), s.attribute(Attribute.Source.class));		
		blk.add(new Code.Label(exitLab));

		return blk;
	}

	protected Block resolve(For s, int freeReg) {		
		String label = Block.freshLabel();
		Pair<CExpr,Block> source = resolve(freeReg,s.source);
		Block blk = new Block();
		Block invariant = null;
		
		if(s.invariant != null) {
			// FIXME: what I should be doing is loading the invariant into the
			// for, and then inlining it in the PreconditionInline state.
			String chklab = Block.freshLabel();
			String entry = Block.freshLabel();				
			invariant = new Block();
			invariant.add(new Code.Check(chklab));
			invariant.addAll(resolveCondition(entry, s.invariant, freeReg));
			invariant.add(new Code.Fail("loop invariant not satisfied"), s
					.attribute(Attribute.Source.class));
			invariant.add(new Code.Label(entry));
			invariant.add(new Code.CheckEnd(chklab));
			blk.addAll(Block.relabel(invariant));
		}
		
		blk.addAll(source.second());
		CExpr.Register reg = CExpr.REG(Type.T_ANY, freeReg); 
		blk.add(new Code.Forall(label, invariant, reg, source.first()), s
				.attribute(Attribute.Source.class));
				
		HashMap<String,CExpr> binding = new HashMap<String,CExpr>();
		binding.put(s.variable,reg);
				
		for (Stmt st : s.body) {
			Block b = resolve(st, freeReg+1);
			blk.addAll(Block.substitute(binding, b));
		}
				
		if(s.invariant != null) {
			String chklab = Block.freshLabel();				
			String loopend = Block.freshLabel();
			blk.add(new Code.Check(chklab));
			blk.addAll(resolveCondition(loopend, s.invariant, freeReg));
			blk.add(new Code.Fail("loop invariant not restored"), s
					.attribute(Attribute.Source.class));
			blk.add(new Code.Label(loopend));
			blk.add(new Code.CheckEnd(chklab));
		}		
		
		blk.add(new Code.ForallEnd(label), s.attribute(Attribute.Source.class));		

		return blk;
	}
	
	/**
	 * Target gives the name of the register to use to store the result of this
	 * expression in.
	 * 
	 * @param target
	 * @param e
	 * @param environment
	 * @return
	 */
	protected Block resolveCondition(String target, Expr e, int freeReg) {
		try {
			if (e instanceof Constant) {
				return resolveCondition(target, (Constant) e, freeReg);
			} else if (e instanceof Variable) {
				return resolveCondition(target, (Variable) e, freeReg);
			} else if (e instanceof BinOp) {
				return resolveCondition(target, (BinOp) e, freeReg);
			} else if (e instanceof UnOp) {
				return resolveCondition(target, (UnOp) e, freeReg);
			} else if (e instanceof Invoke) {
				return resolveCondition(target, (Invoke) e, freeReg);
			} else if (e instanceof RecordAccess) {
				return resolveCondition(target, (RecordAccess) e, freeReg);
			} else if (e instanceof RecordGen) {
				return resolveCondition(target, (RecordGen) e, freeReg);
			} else if (e instanceof TupleGen) {
				return resolveCondition(target, (TupleGen) e, freeReg);
			} else if (e instanceof ListAccess) {
				return resolveCondition(target, (ListAccess) e, freeReg);
			} else if (e instanceof Comprehension) {
				return resolveCondition(target, (Comprehension) e, freeReg);
			} else {
				syntaxError("expected boolean expression, got: "
						+ e.getClass().getName(), filename, e);
			}
		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			syntaxError("internal failure", filename, e, ex);
		}

		return null;
	}

	protected Block resolveCondition(String target, Constant c, int freeReg) {
		Value.Bool b = (Value.Bool) c.value;
		Block blk = new Block();
		if (b.value) {
			blk.add(new Code.Goto(target));
		} else {
			// do nout
		}
		return blk;
	}

	protected Block resolveCondition(String target, Variable v, int freeReg) throws ResolveError {
		CExpr lhs = null;
		Block blk = new Block();
		// First, check whether this is an alias or a 
		Attributes.Alias alias = v.attribute(Attributes.Alias.class);					
		Attributes.Module mod = v.attribute(Attributes.Module.class);
		Type.Fun tf = null;
		
		if(currentFunDecl != null) {
			tf = currentFunDecl.attribute(Attributes.Fun.class).type;
		}			
		
		// Second, see if it's a field of the receiver
		if (alias != null) {
			if(alias.alias != null) {				
				Pair<CExpr, Block> p = resolve(freeReg, alias.alias);
				blk.addAll(p.second());
				lhs = p.first();
			} else {				
				// Ok, must be a local variable
				lhs = CExpr.VAR(Type.T_ANY, v.var);	
			}
		} else if(tf != null && tf.receiver != null) {
			Type pt = tf.receiver;			
			if(pt instanceof Type.Process) {
				Type.Record ert = Type.effectiveRecordType(((Type.Process)pt).element);
				if(ert != null && ert.types.containsKey(v.var)) {
					// Bingo, this is an implicit field dereference
					CExpr thiz = CExpr.UNOP(CExpr.UOP.PROCESSACCESS, CExpr.VAR(
							Type.T_ANY, "this"));					
					lhs = CExpr.RECORDACCESS(thiz, v.var);					
				}
			}
		} else if (mod != null) {
			NameID name = new NameID(mod.module, v.var);
			Value val = constants.get(name);
			if (val == null) {
				// indicates a non-local constant definition
				Module mi = loader.loadModule(mod.module);
				val = mi.constant(v.var).constant();
			}
			lhs = val;
		} 
		
		if(lhs == null) {
			syntaxError("unknown variable \"" + v.var + "\"",filename,v);
			return null;
		}
						
		blk.add(new Code.IfGoto(Code.COP.EQ, lhs, Value.V_BOOL(true), target),
				v.attribute(Attribute.Source.class));			
		
		return blk;
	}

	protected Block resolveCondition(String target, BinOp v, int freeReg) {
		BOp bop = v.op;
		Block blk = new Block();

		if (bop == BOp.OR) {
			blk.addAll(resolveCondition(target, v.lhs, freeReg));
			blk.addAll(resolveCondition(target, v.rhs, freeReg));
			return blk;
		} else if (bop == BOp.AND) {
			String exitLabel = Block.freshLabel();
			blk.addAll(resolveCondition(exitLabel, invert(v.lhs), freeReg));
			blk.addAll(resolveCondition(target, v.rhs, freeReg));
			blk.add(new Code.Label(exitLabel));			
			return blk;
		} else if (bop == BOp.TYPEEQ || bop == BOp.TYPEIMPLIES) {
			return resolveTypeCondition(target, v, freeReg);
		}

		Pair<CExpr, Block> lhs_tb = resolve(freeReg, v.lhs);
		Pair<CExpr, Block> rhs_tb = resolve(freeReg + 1, v.rhs);
		blk.addAll(lhs_tb.second());
		blk.addAll(rhs_tb.second());

		if (bop == BOp.LT || bop == BOp.LTEQ || bop == BOp.GT
				|| bop == BOp.GTEQ) {
			blk.add(new Code.IfGoto(OP2COP(bop, v), lhs_tb.first(), rhs_tb
					.first(), target), v.attribute(Attribute.Source.class));
			return blk;
		} else if (bop == BOp.SUBSET || bop == BOp.SUBSETEQ) {
			blk.add(new Code.IfGoto(OP2COP(bop, v), lhs_tb.first(), rhs_tb
					.first(), target), v.attribute(Attribute.Source.class));
			return blk;
		} else if (bop == BOp.EQ || bop == BOp.NEQ) {
			blk.add(new Code.IfGoto(OP2COP(bop, v), lhs_tb.first(), rhs_tb
					.first(), target), v.attribute(Attribute.Source.class));
			return blk;
		} else if (bop == BOp.ELEMENTOF) {
			blk.add(new Code.IfGoto(OP2COP(bop, v), lhs_tb.first(), rhs_tb
					.first(), target), v.attribute(Attribute.Source.class));
			return blk;
		}

		syntaxError("expected boolean expression", filename, v);
		return null;
	}

	protected Block resolveTypeCondition(String target, BinOp v, int freeReg) {

		Pair<CExpr, Block> lhs_tb = resolve(freeReg, v.lhs);
		Pair<Type, Block> rhs_tb = resolve(((Expr.TypeConst) v.rhs).type);
		
		Block blk = new Block();
		String exitLabel = Block.freshLabel();
		blk.addAll(lhs_tb.second());
		blk.add(new Code.IfGoto(Code.COP.NSUBTYPEEQ, lhs_tb.first(), Value
				.V_TYPE(rhs_tb.first()), exitLabel), v
				.attribute(Attribute.Source.class));

		if (rhs_tb.second() != null) {
			// Chain failures to redirect to the next point.			
			// Create binding for $  
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$", lhs_tb.first());
			// add constraint
			Block constraint = new Block();
			Block.addCheck(freeReg,constraint,rhs_tb.second(),binding,v);
			blk.addAll(Block.chain(exitLabel,constraint));
		} 
		
		blk.add(new Code.Goto(target));	
		blk.add(new Code.Label(exitLabel));
				
		return blk;
	}

	protected Block resolveCondition(String target, UnOp v, int freeReg) {
		UOp uop = v.op;
		switch (uop) {
		case NOT:
			String label = Block.freshLabel();
			Block blk = resolveCondition(label, v.mhs, freeReg);
			blk.add(new Code.Goto(target));
			blk.add(new Code.Label(label));
			return blk;
		}
		syntaxError("expected boolean expression", filename, v);
		return null;
	}

	protected Block resolveCondition(String target, ListAccess v, int freeReg) {
		Pair<CExpr, Block> la = resolve(freeReg, v);
		CExpr lhs = la.first();
		Block blk = la.second();
		blk.add(new Code.IfGoto(Code.COP.EQ, lhs, Value.V_BOOL(true), target),
				v.attribute(Attribute.Source.class));
		return blk;
	}

	protected Block resolveCondition(String target, RecordAccess v, int freeReg) {
		Pair<CExpr, Block> la = resolve(freeReg, v);
		CExpr lhs = la.first();
		Block blk = la.second();
		blk.add(new Code.IfGoto(Code.COP.EQ, lhs, Value.V_BOOL(true), target),
				v.attribute(Attribute.Source.class));
		return blk;
	}

	protected Block resolveCondition(String target, Invoke v, int freeReg) throws ResolveError {
		Pair<CExpr, Block> la = resolve(freeReg, v);
		CExpr lhs = la.first();
		Block blk = la.second();
		blk.add(new Code.IfGoto(Code.COP.EQ, lhs, Value.V_BOOL(true), target),
				v.attribute(Attribute.Source.class));
		return blk;
	}

	protected Block resolveCondition(String target, Comprehension e,
			int freeReg) {
		if (e.cop != Expr.COp.NONE && e.cop != Expr.COp.SOME) {
			syntaxError("expected boolean expression", filename, e);
		}
		
		Block blk = new Block();
		ArrayList<Pair<CExpr.Register, CExpr>> sources = new ArrayList();
		HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
		for (Pair<String, Expr> src : e.sources) {
			Pair<CExpr, Block> r = resolve(freeReg, src.second());
			CExpr.Register reg = CExpr.REG(Type.T_ANY, freeReg++);
			sources.add(new Pair<CExpr.Register, CExpr>(reg, r.first()));
			binding.put(src.first(), reg);
			blk.addAll(r.second());			
		}

		ArrayList<String> labels = new ArrayList<String>();
		for (Pair<CExpr.Register, CExpr> ent : sources) {
			String loopLabel = Block.freshLabel();
			labels.add(loopLabel);
			blk
					.add(new Code.Forall(loopLabel, null, ent.first(), ent
							.second()), e.attribute(Attribute.Source.class));
		}
		if (e.cop == Expr.COp.NONE) {
			String exitLabel = Block.freshLabel();
			blk.addAll(resolveCondition(exitLabel, e.condition, freeReg));
			for (int i = (labels.size() - 1); i >= 0; --i) {
				blk.add(new Code.ForallEnd(labels.get(i)));
			}
			blk.add(new Code.Goto(target));
			blk.add(new Code.Label(exitLabel));
		} else { // SOME
			blk.addAll(resolveCondition(target, e.condition, freeReg));
			for (int i = (labels.size() - 1); i >= 0; --i) {
				blk.add(new Code.ForallEnd(labels.get(i)));
			}
		} // ALL, LONE and ONE will be harder

		// Finally, we need to substitute the block to rename all occurrences of
		// the quantified variables to be their actual registers.
		blk = Block.substitute(binding, blk);

		return blk;
	}

	/**
	 * Translate an expression in the context of a given type environment. The
	 * "freeReg" --- free register --- identifies the first free register for
	 * use as temporary storage. An expression differs from a statement in that
	 * it may consume a register as part of the translation. Thus, compound
	 * expressions, such as binop, will save the freeReg of one expression from
	 * being used when translating a subsequent expression.
	 * 
	 * @param freeReg
	 * @param e
	 * @param environment
	 * @return
	 */
	protected Pair<CExpr, Block> resolve(int freeReg, Expr e) {
		try {
			if (e instanceof Constant) {
				return resolve(freeReg, (Constant) e);
			} else if (e instanceof Variable) {
				return resolve(freeReg, (Variable) e);
			} else if (e instanceof NaryOp) {
				return resolve(freeReg, (NaryOp) e);
			} else if (e instanceof BinOp) {
				return resolve(freeReg, (BinOp) e);
			} else if (e instanceof ListAccess) {
				return resolve(freeReg, (ListAccess) e);
			} else if (e instanceof UnOp) {
				return resolve(freeReg, (UnOp) e);
			} else if (e instanceof Invoke) {
				return resolve(freeReg, (Invoke) e);
			} else if (e instanceof Comprehension) {
				return resolve(freeReg, (Comprehension) e);
			} else if (e instanceof RecordAccess) {
				return resolve(freeReg, (RecordAccess) e);
			} else if (e instanceof RecordGen) {
				return resolve(freeReg, (RecordGen) e);
			} else if (e instanceof TupleGen) {
				return resolve(freeReg, (TupleGen) e);
			} else {
				syntaxError("unknown expression encountered: "
						+ e.getClass().getName(), filename, e);
			}
		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			syntaxError("internal failure", filename, e, ex);
		}

		return null;
	}

	protected Pair<CExpr, Block> resolve(int freeReg, Invoke s) throws ResolveError {
		List<Expr> args = s.arguments;
		Block blk = new Block();

		int idx = freeReg;
		ArrayList<CExpr> nargs = new ArrayList<CExpr>();
		CExpr receiver = null;
		
		if (s.receiver != null) {
			Pair<CExpr, Block> tb = resolve(idx++, s.receiver);
			receiver = tb.first();
			blk.addAll(tb.second());
		}

		for (Expr e : args) {
			Pair<CExpr, Block> e_tb = resolve(idx++, e);
			nargs.add(e_tb.first());
			blk.addAll(e_tb.second());
		}	
		
		Attributes.Module modInfo = s.attribute(Attributes.Module.class);
		NameID name = new NameID(modInfo.module, s.name);

		return new Pair<CExpr, Block>(CExpr.INVOKE(
				Type.T_FUN(null, Type.T_ANY), name, 0, receiver, nargs), blk);
	}

	protected Pair<CExpr, Block> resolve(int freeReg, Constant c) {
		return new Pair<CExpr, Block>(c.value, new Block());
	}

	protected Pair<CExpr, Block> resolve(int target, Variable v) throws ResolveError {
		// First, check if this is an alias or not				
		
		Attributes.Alias alias = v.attribute(Attributes.Alias.class);
		if (alias != null) {
			// Must be a local variable	
			if(alias.alias == null) {				
				return new Pair<CExpr, Block>(CExpr.VAR(Type.T_ANY, v.var), new Block());
			} else {								
				return resolve(0, alias.alias);
			}
		}
		
		if(currentFunDecl != null) {
			Type.Fun tf = currentFunDecl.attribute(Attributes.Fun.class).type;

			// Second, see if it's a field of the receiver
			if(tf.receiver != null) {
				Type pt = tf.receiver;				
				if(pt instanceof Type.Process) {
					Type.Record ert = Type.effectiveRecordType(((Type.Process)pt).element);
					if(ert != null && ert.types.containsKey(v.var)) {						
						// Bingo, this is an implicit field dereference
						CExpr thiz = CExpr.UNOP(CExpr.UOP.PROCESSACCESS, CExpr.VAR(
								Type.T_ANY, "this"));					
						CExpr.RecordAccess ra = CExpr.RECORDACCESS(thiz, v.var);
						return new Pair<CExpr,Block>(ra, new Block());
					}
				}
			}
		}
		
		// Third, see if it's a constant
		Attributes.Module mod = v.attribute(Attributes.Module.class);
		if (mod != null) {
			NameID name = new NameID(mod.module, v.var);
			Value val = constants.get(name);
			if (val == null) {
				// indicates a non-local constant definition
				Module mi = loader.loadModule(mod.module);
				val = mi.constant(v.var).constant();
			}
			return new Pair<CExpr, Block>(val, new Block());
		}
				
		// must be an error
		syntaxError("unknown variable \"" + v.var + "\"",filename,v);
		return null;
	}

	protected Pair<CExpr, Block> resolve(int freeReg, UnOp v) {
		Pair<CExpr, Block> mhs = resolve(freeReg, v.mhs);
		Block blk = mhs.second();
		switch (v.op) {
		case NEG:
			return new Pair<CExpr, Block>(CExpr
					.UNOP(CExpr.UOP.NEG, mhs.first()), blk);
		case NOT:
			String falseLabel = Block.freshLabel();
			String exitLabel = Block.freshLabel();
			// Ok, so there's a bit of inefficiency here ... so what?
			blk = resolveCondition(falseLabel, v.mhs, freeReg);
			blk.add(new Code.Assign(CExpr.REG(Type.T_BOOL, freeReg), Value
					.V_BOOL(true)), v.attribute(Attribute.Source.class));
			blk.add(new Code.Goto(exitLabel));
			blk.add(new Code.Label(falseLabel));
			blk.add(new Code.Assign(CExpr.REG(Type.T_BOOL, freeReg), Value
					.V_BOOL(false)), v.attribute(Attribute.Source.class));
			blk.add(new Code.Label(exitLabel));
			return new Pair<CExpr, Block>(CExpr.REG(Type.T_BOOL, freeReg), blk);
		case LENGTHOF:
			return new Pair<CExpr, Block>(CExpr.UNOP(CExpr.UOP.LENGTHOF, mhs
					.first()), blk);
		case PROCESSACCESS:			
			return new Pair<CExpr, Block>(CExpr.UNOP(CExpr.UOP.PROCESSACCESS,
					mhs.first()), blk);
		case PROCESSSPAWN:
			return new Pair<CExpr, Block>(CExpr.UNOP(CExpr.UOP.PROCESSSPAWN,
					mhs.first()), blk);
		default:
			syntaxError("unexpected unary operator encountered", filename, v);
			return null;
		}
	}

	protected Pair<CExpr, Block> resolve(int freeReg, ListAccess v) {
		Pair<CExpr, Block> lhs_tb = resolve(freeReg, v.src);
		Pair<CExpr, Block> rhs_tb = resolve(freeReg + 1, v.index);
		Block blk = new Block();
		blk.addAll(lhs_tb.second());
		blk.addAll(rhs_tb.second());
		return new Pair<CExpr, Block>(CExpr.LISTACCESS(lhs_tb.first(), rhs_tb
				.first()), blk);
	}

	protected Pair<CExpr, Block> resolve(int freeReg, BinOp v) {

		// could probably use a range test for this somehow
		if (v.op == BOp.EQ || v.op == BOp.NEQ || v.op == BOp.LT
				|| v.op == BOp.LTEQ || v.op == BOp.GT || v.op == BOp.GTEQ
				|| v.op == BOp.SUBSET || v.op == BOp.SUBSETEQ
				|| v.op == BOp.ELEMENTOF || v.op == BOp.AND || v.op == BOp.OR) {
			String trueLabel = Block.freshLabel();
			String exitLabel = Block.freshLabel();
			Block blk = resolveCondition(trueLabel, v, freeReg);
			blk.add(new Code.Assign(CExpr.REG(Type.T_BOOL, freeReg), Value
					.V_BOOL(false)), v.attribute(Attribute.Source.class));
			blk.add(new Code.Goto(exitLabel));
			blk.add(new Code.Label(trueLabel));
			blk.add(new Code.Assign(CExpr.REG(Type.T_BOOL, freeReg), Value
					.V_BOOL(true)), v.attribute(Attribute.Source.class));
			blk.add(new Code.Label(exitLabel));			
			return new Pair<CExpr, Block>(CExpr.REG(Type.T_BOOL, freeReg), blk);
		}

		Pair<CExpr, Block> lhs_tb = resolve(freeReg, v.lhs);
		Pair<CExpr, Block> rhs_tb = resolve(freeReg + 1, v.rhs);
		BOp bop = v.op;

		Block blk = new Block();
		blk.addAll(lhs_tb.second());
		blk.addAll(rhs_tb.second());

		if (bop == BOp.ADD || bop == BOp.SUB || bop == BOp.MUL
				|| bop == BOp.DIV) {
			CExpr r = CExpr.BINOP(OP2BOP(bop, v), lhs_tb.first(), rhs_tb
					.first());
			return new Pair<CExpr, Block>(r, blk);			
		} else if (bop == BOp.UNION || bop == BOp.INTERSECTION) {
			CExpr r = CExpr.BINOP(OP2BOP(bop, v), lhs_tb.first(), rhs_tb
					.first());
			return new Pair<CExpr, Block>(r, blk);
		}

		syntaxError("unknown binary operation encountered", filename, v);
		return null;
	}

	protected Pair<CExpr, Block> resolve(int freeReg, NaryOp v) {
		Block blk = new Block();
		if (v.nop == NOp.SUBLIST) {
			if (v.arguments.size() != 3) {
				syntaxError("incorrect number of arguments", filename, v);
			}
			Pair<CExpr, Block> src = resolve(freeReg, v.arguments.get(0));
			Pair<CExpr, Block> start = resolve(freeReg + 1, v.arguments.get(1));
			Pair<CExpr, Block> end = resolve(freeReg + 2, v.arguments.get(2));
			blk.addAll(src.second());
			blk.addAll(start.second());
			blk.addAll(end.second());
			CExpr r = CExpr.NARYOP(CExpr.NOP.SUBLIST, src.first(), start
					.first(), end.first());
			return new Pair<CExpr, Block>(r, blk);
		} else {
			int idx = freeReg;
			ArrayList<CExpr> args = new ArrayList<CExpr>();
			for (Expr e : v.arguments) {
				Pair<CExpr, Block> t = resolve(idx++, e);
				args.add(t.first());
				blk.addAll(t.second());
			}

			if (v.nop == NOp.LISTGEN) {
				return new Pair<CExpr, Block>(CExpr.NARYOP(CExpr.NOP.LISTGEN,
						args), blk);
			} else {
				return new Pair<CExpr, Block>(CExpr.NARYOP(CExpr.NOP.SETGEN,
						args), blk);
			}
		}
	}

	protected Pair<CExpr, Block> resolve(int freeReg, Comprehension e) {

		// First, check for boolean cases which are handled mostly by
		// resolveCondition.
		if (e.cop == Expr.COp.SOME || e.cop == Expr.COp.NONE) {
			String trueLabel = Block.freshLabel();
			String exitLabel = Block.freshLabel();
			Block blk = resolveCondition(trueLabel, e, freeReg);
			blk.add(new Code.Assign(CExpr.REG(Type.T_BOOL, freeReg), Value
					.V_BOOL(false)), e.attribute(Attribute.Source.class));
			blk.add(new Code.Goto(exitLabel));
			blk.add(new Code.Label(trueLabel));
			blk.add(new Code.Assign(CExpr.REG(Type.T_BOOL, freeReg), Value
					.V_BOOL(true)), e.attribute(Attribute.Source.class));
			blk.add(new Code.Label(exitLabel));
			return new Pair<CExpr, Block>(CExpr.REG(Type.T_BOOL, freeReg), blk);
		}

		// Ok, non-boolean case.		
		ArrayList<Pair<CExpr.Register, CExpr>> sources = new ArrayList();
		Block blk = new Block();
		HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
		for (Pair<String, Expr> src : e.sources) {
			Pair<CExpr, Block> r = resolve(0, src.second());
			CExpr.Register reg = CExpr.REG(Type.T_ANY, freeReg++);
			sources.add(new Pair<CExpr.Register, CExpr>(reg, r.first()));
			binding.put(src.first(), reg);
			blk.addAll(r.second());			
		}

		Pair<CExpr, Block> value = resolve(freeReg + 1, e.value);
		Type type = value.first().type();
		
		CExpr.Register lhs;

		if (e.cop == Expr.COp.LISTCOMP) {
			lhs = CExpr.REG(Type.T_LIST(type), freeReg);
			blk.add(new Code.Assign(lhs, CExpr.NARYOP(CExpr.NOP.LISTGEN)), e
					.attribute(Attribute.Source.class));
		} else {
			lhs = CExpr.REG(Type.T_SET(type), freeReg);
			blk.add(new Code.Assign(lhs, CExpr.NARYOP(CExpr.NOP.SETGEN)), e
					.attribute(Attribute.Source.class));
		}

		Block loopInvariant = null;
		
		// At this point, it would be good to determine an appropriate loop
		// invariant for a set comprehension. This is easy enough in the case of
		// a single variable comprehension, but actually rather difficult for a
		// multi-variable comprehension.
		//
		// For example, consider <code>{x+y | x in xs, y in ys, x<0 && y<0}</code>
		// 
		// What is an appropriate loop invariant here?
		
		String continueLabel = Block.freshLabel();
		ArrayList<String> labels = new ArrayList<String>();
		for (Pair<CExpr.Register, CExpr> ent : sources) {
			String loopLabel = Block.freshLabel();
			labels.add(loopLabel);

			blk
					.add(new Code.Forall(loopLabel, loopInvariant, ent.first(), ent
							.second()), e.attribute(Attribute.Source.class));
		}
		
		if (e.condition != null) {
			blk.addAll(resolveCondition(continueLabel, invert(e.condition),
					freeReg));
			blk.addAll(value.second());
			blk.add(new Code.Assign(lhs, CExpr.BINOP(CExpr.BOP.UNION, lhs,
					CExpr.NARYOP(CExpr.NOP.SETGEN, value.first()))), e
					.attribute(Attribute.Source.class));
			blk.add(new Code.Label(continueLabel));
		} else {
			blk.addAll(value.second());
			blk.add(new Code.Assign(lhs, CExpr.BINOP(CExpr.BOP.UNION, lhs,
					CExpr.NARYOP(CExpr.NOP.SETGEN, value.first()))), e
					.attribute(Attribute.Source.class));
		}

		for (int i = (labels.size() - 1); i >= 0; --i) {
			blk.add(new Code.ForallEnd(labels.get(i)));
		}

		// Finally, we need to substitute the block to rename all occurrences of
		// the quantified variables to be their actual registers.
		blk = Block.substitute(binding, blk);

		return new Pair<CExpr, Block>(lhs, blk);
	}

	protected Pair<CExpr, Block> resolve(int freeReg, RecordGen sg) {
		HashMap<String, CExpr> values = new HashMap<String, CExpr>();
		Block blk = new Block();
		for (Map.Entry<String, Expr> e : sg.fields.entrySet()) {
			Pair<CExpr, Block> tb = resolve(freeReg, e.getValue());
			values.put(e.getKey(), tb.first());
			blk.addAll(tb.second());
		}
		return new Pair<CExpr, Block>(CExpr.RECORD(values), blk);
	}

	protected Pair<CExpr, Block> resolve(int freeReg, TupleGen sg) {
		HashMap<String, CExpr> values = new HashMap<String, CExpr>();
		Block blk = new Block();
		int idx=0;
		for (Expr e : sg.fields) {
			String name = "$" + idx++;
			Pair<CExpr, Block> tb = resolve(freeReg, e	);
			values.put(name, tb.first());
			blk.addAll(tb.second());
		}
		return new Pair<CExpr, Block>(CExpr.RECORD(values), blk);
	}

	protected Pair<CExpr, Block> resolve(int freeReg, RecordAccess sg) {
		Pair<CExpr, Block> lhs = resolve(freeReg, sg.lhs);		
		return new Pair<CExpr, Block>(CExpr.RECORDACCESS(lhs.first(), sg.name),
				lhs.second());
	}

	protected Pair<Type, Block> resolve(UnresolvedType t) {
		if (t instanceof UnresolvedType.Any) {
			return new Pair<Type, Block>(Type.T_ANY, null);
		} else if (t instanceof UnresolvedType.Existential) {
			return new Pair<Type, Block>(Type.T_EXISTENTIAL, null);
		} else if (t instanceof UnresolvedType.Void) {
			return new Pair<Type, Block>(Type.T_VOID, null);
		} else if (t instanceof UnresolvedType.Null) {
			return new Pair<Type, Block>(Type.T_NULL, null);
		} else if (t instanceof UnresolvedType.Bool) {
			return new Pair<Type, Block>(Type.T_BOOL, null);
		} else if (t instanceof UnresolvedType.Int) {
			return new Pair<Type, Block>(Type.T_INT, null);
		} else if (t instanceof UnresolvedType.Real) {
			return new Pair<Type, Block>(Type.T_REAL, null);
		} else if (t instanceof UnresolvedType.List) {
			UnresolvedType.List lt = (UnresolvedType.List) t;
			Pair<Type, Block> p = resolve(lt.element);
			Type rt = Type.T_LIST(p.first());
			String label = Block.freshLabel();
			Block blk = null;
			if (p.second() != null) {
				blk = new Block();
				CExpr.Register reg = CExpr.REG(p.first(), 0);
				// FIXME: need some line number information here?
				blk.add(new Code.Forall(label, null, reg, CExpr.VAR(rt, "$")));
				blk.addAll(Block.substitute("$", reg, Block.registerShift(1, p
						.second())));
				blk.add(new Code.ForallEnd(label));
			}
			return new Pair<Type, Block>(rt, blk);
		} else if (t instanceof UnresolvedType.Set) {
			UnresolvedType.Set st = (UnresolvedType.Set) t;
			Pair<Type, Block> p = resolve(st.element);
			Type rt = Type.T_SET(p.first());
			String label = Block.freshLabel();
			Block blk = null;
			if (p.second() != null) {
				blk = new Block();
				CExpr.Register reg = CExpr.REG(p.first(), 0);
				// FIXME: need some line number information here?
				blk.add(new Code.Forall(label, null, reg, CExpr.VAR(rt, "$")));
				blk.addAll(Block.substitute("$", reg, Block.registerShift(1, p
						.second())));
				blk.add(new Code.ForallEnd(label));
			}
			return new Pair<Type, Block>(rt, blk);
		} else if (t instanceof UnresolvedType.Tuple) {
			// At the moment, a tuple is compiled down to a wyil record.
			UnresolvedType.Tuple tt = (UnresolvedType.Tuple) t;
			HashMap<String,Type> types = new HashMap<String,Type>();
			Block blk = null;
			CExpr.Variable tmp = CExpr.VAR(Type.T_VOID, "$");
			int idx=0;
			for (UnresolvedType e : tt.types) {
				String name = "$" + idx++;
				Pair<Type, Block> p = resolve(e);
				types.put(name, p.first());
				if (p.second() != null) {
					if (blk == null) {
						blk = new Block();
					}
					HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
					binding.put("$", CExpr.RECORDACCESS(tmp, name));
					// FIXME: possible bug here for union types
					blk.addAll(Block.substitute(binding, p.second()));
				}
			}
			Type type = Type.T_RECORD(types);
			// Need to update the self type properly
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$", CExpr.VAR(type, "$"));
			return new Pair<Type, Block>(type, Block.substitute(binding, blk));
		} else if (t instanceof UnresolvedType.Record) {		
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			HashMap<String, Type> types = new HashMap<String, Type>();
			Block blk = null;
			CExpr.Variable tmp = CExpr.VAR(Type.T_VOID, "$");
			for (Map.Entry<String, UnresolvedType> e : tt.types.entrySet()) {
				Pair<Type, Block> p = resolve(e.getValue());
				types.put(e.getKey(), p.first());
				if (p.second() != null) {
					if (blk == null) {
						blk = new Block();
					}
					HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
					binding.put("$", CExpr.RECORDACCESS(tmp, e.getKey()));
					blk.addAll(Block.substitute(binding, p.second()));
				}
			}
			Type type = Type.T_RECORD(types);
			// Need to update the self type properly
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$", CExpr.VAR(type, "$"));
			return new Pair<Type, Block>(type, Block.substitute(binding, blk));
		} else if (t instanceof UnresolvedType.Named) {
			UnresolvedType.Named dt = (UnresolvedType.Named) t;
			ModuleID mid = dt.attribute(Attributes.Module.class).module;
			if (modules.contains(mid)) {
				Pair<Type, Block> p = types.get(new NameID(mid, dt.name));				
				return new Pair<Type, Block>(p.first(), Block.relabel(p
						.second()));
			} else {
				try {
					Module mi = loader.loadModule(mid);
					Module.TypeDef td = mi.type(dt.name);
					return new Pair<Type, Block>(td.type(), Block.relabel(td
							.constraint()));
				} catch (ResolveError rex) {
					syntaxError(rex.getMessage(), filename, t, rex);
					return null;
				}
			}
		} else if (t instanceof UnresolvedType.Union) {
			UnresolvedType.Union ut = (UnresolvedType.Union) t;
			HashSet<Type.NonUnion> bounds = new HashSet<Type.NonUnion>();
			Block blk = new Block();			
			String exitLabel = Block.freshLabel();
			CExpr.Variable var = CExpr.VAR(Type.T_VOID, "$#");
			for (UnresolvedType b : ut.bounds) {
				Pair<Type, Block> p = resolve(b);
				Type bt = p.first();
				if (bt instanceof Type.NonUnion) {
					bounds.add((Type.NonUnion) bt);
				} else {
					bounds.addAll(((Type.Union) bt).bounds);
				}
			
				if(p.second() != null) {
					String nextLabel = Block.freshLabel();
					blk.add(new Code.IfGoto(Code.COP.NSUBTYPEEQ, var, Value
						.V_TYPE(p.first()), nextLabel));		
					blk.addAll(Block.chain(nextLabel, p.second()));				
					blk.add(new Code.Goto(exitLabel));
					blk.add(new Code.Label(nextLabel));
				} else {
					blk.add(new Code.IfGoto(Code.COP.SUBTYPEEQ, var, Value
							.V_TYPE(p.first()), exitLabel));						
				}
			}

			// FIXME: need some line number information here
			blk.add(new Code.Fail("type constraint not satisfied"));
			blk.add(new Code.Label(exitLabel));

			Type type;
			if (bounds.size() == 1) {
				type = bounds.iterator().next();
			} else {
				type = Type.leastUpperBound(bounds);
			}
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$#", CExpr.VAR(type, "$"));
			return new Pair<Type, Block>(type, Block.substitute(binding, blk));
		} else {
			UnresolvedType.Process ut = (UnresolvedType.Process) t;
			Pair<Type, Block> p = resolve(ut.element);			
			Type type = Type.T_PROCESS(p.first());
			HashMap<String, CExpr> binding = new HashMap<String, CExpr>();
			binding.put("$", CExpr.UNOP(CExpr.UOP.PROCESSACCESS, CExpr.VAR(type, "$")));			
			return new Pair<Type, Block>(type, Block.substitute(binding, p.second()));					
		}
	}

	public Variable flattern(Expr e) {
		if (e instanceof Variable) {
			return (Variable) e;
		} else if (e instanceof ListAccess) {
			ListAccess la = (ListAccess) e;
			return flattern(la.src);
		} else if (e instanceof RecordAccess) {
			RecordAccess la = (RecordAccess) e;
			return flattern(la.lhs);
		} else if (e instanceof UnOp) {
			UnOp la = (UnOp) e;
			if (la.op == Expr.UOp.PROCESSACCESS) {
				return flattern(la.mhs);
			}
		}
		syntaxError("invalid lval", filename, e);
		return null;
	}

	public static Expr invert(Expr e) {
		if (e instanceof Expr.BinOp) {
			BinOp bop = (BinOp) e;
			switch (bop.op) {
			case AND:
				return new BinOp(BOp.OR, invert(bop.lhs), invert(bop.rhs), e
						.attributes());
			case OR:
				return new BinOp(BOp.AND, invert(bop.lhs), invert(bop.rhs), e
						.attributes());
			case EQ:
				return new BinOp(BOp.NEQ, bop.lhs, bop.rhs, e.attributes());
			case NEQ:
				return new BinOp(BOp.EQ, bop.lhs, bop.rhs, e.attributes());
			case LT:
				return new BinOp(BOp.GTEQ, bop.lhs, bop.rhs, e.attributes());
			case LTEQ:
				return new BinOp(BOp.GT, bop.lhs, bop.rhs, e.attributes());
			case GT:
				return new BinOp(BOp.LTEQ, bop.lhs, bop.rhs, e.attributes());
			case GTEQ:
				return new BinOp(BOp.LT, bop.lhs, bop.rhs, e.attributes());
			}
		} else if (e instanceof Expr.UnOp) {
			UnOp uop = (UnOp) e;
			switch (uop.op) {
			case NOT:
				return uop.mhs;
			}
		}
		return new Expr.UnOp(Expr.UOp.NOT, e);
	}

	public CExpr.BOP OP2BOP(Expr.BOp bop, SyntacticElement elem) {
		switch (bop) {
		case ADD:
			return CExpr.BOP.ADD;
		case SUB:
			return CExpr.BOP.SUB;
		case DIV:
			return CExpr.BOP.DIV;
		case MUL:
			return CExpr.BOP.MUL;
		case UNION:
			return CExpr.BOP.UNION;
		case INTERSECTION:
			return CExpr.BOP.INTERSECT;
		}
		syntaxError("unrecognised binary operation", filename, elem);
		return null;
	}

	public Code.COP OP2COP(Expr.BOp bop, SyntacticElement elem) {
		switch (bop) {
		case EQ:
			return Code.COP.EQ;
		case NEQ:
			return Code.COP.NEQ;
		case LT:
			return Code.COP.LT;
		case LTEQ:
			return Code.COP.LTEQ;
		case GT:
			return Code.COP.GT;
		case GTEQ:
			return Code.COP.GTEQ;
		case SUBSET:
			return Code.COP.SUBSET;
		case SUBSETEQ:
			return Code.COP.SUBSETEQ;
		case ELEMENTOF:
			return Code.COP.ELEMOF;
		}
		syntaxError("unrecognised binary operation", filename, elem);
		return null;
	}

	protected <T extends Type> T checkType(Type t, Class<T> clazz,
			SyntacticElement elem) {		
		if (clazz.isInstance(t)) {
			return (T) t;
		} else {
			syntaxError("expected type " + clazz.getName() + ", found " + t,
					filename, elem);
			return null;
		}
	}

	private static int idx = 0;

	public static String freshVar() {
		return "$" + idx++;
	}
}
