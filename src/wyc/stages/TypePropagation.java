// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyc.stages;

import static wyil.util.SyntaxError.*;
import static wyil.util.ErrorMessages.*;

import java.util.*;

import wyc.Resolver;
import wyc.lang.*;
import wyc.lang.WhileyFile.*;
import wyc.util.Context;
import wyc.util.ExpressionTyper;
import wyc.util.Nominal;
import wyc.util.RefCountedHashMap;
import wyil.ModuleLoader;
import wyil.lang.Attribute;
import wyil.lang.ModuleID;
import wyil.lang.NameID;
import wyil.lang.PkgID;
import wyil.lang.Type;
import wyil.lang.Value;
import wyil.util.Pair;
import wyil.util.ResolveError;
import wyil.util.SyntacticElement;
import wyil.util.SyntaxError;

/**
 * Propagates type information in a flow-sensitive fashion from declared
 * parameter and return types through assigned expressions, to determine types
 * for all intermediate expressions and variables. For example:
 * 
 * <pre>
 * int sum([int] data):
 *     r = 0          // infers int type for r, based on type of constant
 *     for v in data: // infers int type for v, based on type of data
 *         r = r + v  // infers int type for r, based on type of operands 
 *     return r       // infers int type for r, based on type of r after loop
 * </pre>
 * 
 * The flash points here are the variables <code>r</code> and <code>v</code> as
 * <i>they do not have declared types</i>. Type propagation is responsible for
 * determing their type.
 * 
 * Loops present an interesting challenge for type propagation. Consider this
 * example:
 * 
 * <pre>
 * real loopy(int max):
 *     i = 0
 *     while i < max:
 *         i = i + 0.5
 *     return i
 * </pre>
 * 
 * On the first pass through the loop, variable <code>i</code> is inferred to
 * have type <code>int</code> (based on the type of the constant <code>0</code>
 * ). However, the add expression is inferred to have type <code>real</code>
 * (based on the type of the rhs) and, hence, the resulting type inferred for
 * <code>i</code> is <code>real</code>. At this point, the loop must be
 * reconsidered taking into account this updated type for <code>i</code>.
 *
 * <h3>References</h3>
 * <ul>
 * <li>
 * <p>
 * David J. Pearce and James Noble. Structural and Flow-Sensitive Types for
 * Whiley. Technical Report, Victoria University of Wellington, 2010.
 * </p>
 * </li>
 * </ul>
 * 
 * @author David J. Pearce
 * 
 */
public final class TypePropagation {
	private final ModuleLoader loader;
	private final Resolver resolver;
	private ArrayList<Scope> scopes = new ArrayList<Scope>();
	private String filename;
	private WhileyFile.FunctionOrMethodOrMessage current;
	
	public TypePropagation(ModuleLoader loader, Resolver resolver) {
		this.loader = loader;
		this.resolver = resolver;
	}
	
	public void propagate(WhileyFile wf) {
		this.filename = wf.filename;
		
		ModuleID mid = wf.module;
		ArrayList<WhileyFile.Import> imports = new ArrayList<WhileyFile.Import>();
		
		imports.add(new WhileyFile.Import(mid.pkg(), mid.module(), "*")); 
		// other import statements are inserted here
		imports.add(new WhileyFile.Import(mid.pkg(), "*", null)); 
		imports.add(new WhileyFile.Import(new PkgID("whiley","lang"), "*", null)); 		
		ExpressionTyper typer = new ExpressionTyper(resolver,new Context(wf,imports));
		
		for(WhileyFile.Declaration decl : wf.declarations) {
			try {
				if (decl instanceof Import) {
					Import impd = (Import) decl;
					// insert import statement into the appropriate space
					// between that for this file, and those for its package and
					// whiley.lang (see above).
					imports.add(1, impd);
					typer = new ExpressionTyper(resolver,new Context(wf,imports));
				} else if(decl instanceof FunctionOrMethodOrMessage) {
					propagate((FunctionOrMethodOrMessage)decl,typer);
				} else if(decl instanceof TypeDef) {
					propagate((TypeDef)decl,typer);					
				} else if(decl instanceof Constant) {
					propagate((Constant)decl,typer.context());					
				}			
			} catch(ResolveError e) {
				syntaxError(errorMessage(RESOLUTION_ERROR,e.getMessage()),filename,decl,e);
			} catch(SyntaxError e) {
				throw e;
			} catch(Throwable t) {
				internalFailure(t.getMessage(),filename,decl,t);
			}
		}
	}
	
	public void propagate(Constant cd, Context context) throws ResolveError {
		NameID nid = new NameID(context.file.module, cd.name);
		cd.resolvedValue = resolver.resolveAsConstant(nid);
	}
	
	public void propagate(TypeDef td, ExpressionTyper typer) throws ResolveError {		
		// first, resolve the declared type
		td.resolvedType = resolver.resolveAsType(td.unresolvedType, typer.context());
		
		if(td.constraint != null) {						
			// second, construct the appropriate typing environment			
			RefCountedHashMap<String,Nominal> environment = new RefCountedHashMap<String,Nominal>();
			environment.put("$", td.resolvedType);
			
			// FIXME: add names exposed from records and other types
			
			// third, propagate type information through the constraint 			
			td.constraint = typer.propagate(td.constraint,environment);
		}
	}

	public void propagate(FunctionOrMethodOrMessage d, ExpressionTyper typer) throws ResolveError {		
		this.current = d; // ugly
		Context context = typer.context();		
		RefCountedHashMap<String,Nominal> environment = new RefCountedHashMap<String,Nominal>();					
		
		for (WhileyFile.Parameter p : d.parameters) {							
			environment = environment.put(p.name,resolver.resolveAsType(p.type,context));
		}
		
		if(d instanceof Message) {
			Message md = (Message) d;							
			environment = environment.put("this",resolver.resolveAsType(md.receiver,context));			
		}
		
		if(d.precondition != null) {
			d.precondition = typer.propagate(d.precondition,environment.clone());
		}
		
		if(d.postcondition != null) {			
			environment = environment.put("$", resolver.resolveAsType(d.ret,context));
			d.postcondition = typer.propagate(d.postcondition,environment.clone());
			// The following is a little sneaky and helps to avoid unnecessary
			// copying of environments. 
			environment = environment.remove("$");
		}

		if(d instanceof Function) {
			Function f = (Function) d;
			f.resolvedType = resolver.resolveAsType(f.unresolvedType(),context);					
		} else if(d instanceof Method) {
			Method m = (Method) d;			
			m.resolvedType = resolver.resolveAsType(m.unresolvedType(),context);		
		} else {
			Message m = (Message) d;
			m.resolvedType = resolver.resolveAsType(m.unresolvedType(),context);		
		}
		
		propagate(d.statements,environment,typer);
	}
	
	private RefCountedHashMap<String, Nominal> propagate(
			ArrayList<Stmt> body,
			RefCountedHashMap<String, Nominal> environment,
			ExpressionTyper typer) {
		
		
		for (int i=0;i!=body.size();++i) {
			Stmt stmt = body.get(i);
			if(stmt instanceof Expr) {
				body.set(i,(Stmt) typer.propagate((Expr)stmt,environment));
			} else {
				environment = propagate(stmt, environment, typer);
			}
		}
		
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt stmt,
			RefCountedHashMap<String, Nominal> environment,
			ExpressionTyper typer) {
				
		try {
			if(stmt instanceof Stmt.Assign) {
				return propagate((Stmt.Assign) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Return) {
				return propagate((Stmt.Return) stmt,environment,typer);
			} else if(stmt instanceof Stmt.IfElse) {
				return propagate((Stmt.IfElse) stmt,environment,typer);
			} else if(stmt instanceof Stmt.While) {
				return propagate((Stmt.While) stmt,environment,typer);
			} else if(stmt instanceof Stmt.ForAll) {
				return propagate((Stmt.ForAll) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Switch) {
				return propagate((Stmt.Switch) stmt,environment,typer);
			} else if(stmt instanceof Stmt.DoWhile) {
				return propagate((Stmt.DoWhile) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Break) {
				return propagate((Stmt.Break) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Throw) {
				return propagate((Stmt.Throw) stmt,environment,typer);
			} else if(stmt instanceof Stmt.TryCatch) {
				return propagate((Stmt.TryCatch) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Assert) {
				return propagate((Stmt.Assert) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Debug) {
				return propagate((Stmt.Debug) stmt,environment,typer);
			} else if(stmt instanceof Stmt.Skip) {
				return propagate((Stmt.Skip) stmt,environment,typer);
			} else {
				internalFailure("unknown statement: " + stmt.getClass().getName(),filename,stmt);
				return null; // deadcode
			}
		} catch(ResolveError e) {
			syntaxError(errorMessage(RESOLUTION_ERROR,e.getMessage()),filename,stmt,e);
			return null; // dead code
		} catch(SyntaxError e) {
			throw e;
		} catch(Throwable e) {
			internalFailure(e.getMessage(),filename,stmt,e);
			return null; // dead code
		}
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Assert stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {
		stmt.expr = typer.propagate(stmt.expr,environment);
		checkIsSubtype(Type.T_BOOL,stmt.expr);
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Assign stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) throws ResolveError {
			
		Expr.LVal lhs = stmt.lhs;
		Expr rhs = typer.propagate(stmt.rhs,environment);
		
		if(lhs instanceof Expr.AbstractVariable) {
			// An assignment to a local variable is slightly different from
			// other kinds of assignments. That's because in this case only it
			// is permitted that the variable does not exist a priori.
			// Therefore, whatever type the rhs has, the variable in question
			// will have after the assignment.
			Expr.AbstractVariable av = (Expr.AbstractVariable) lhs;
			Expr.AssignedVariable lv;
			if(lhs instanceof Expr.AssignedVariable) {
				// this case just avoids creating another object everytime we
				// visit this statement.
				lv = (Expr.AssignedVariable) lhs; 
			} else {
				lv = new Expr.AssignedVariable(av.var, av.attributes());
			}
			lv.type = Nominal.T_VOID;
			lv.afterType = rhs.result();			
			environment = environment.put(lv.var, lv.afterType);
			lhs = lv;
		} else if(lhs instanceof Expr.Tuple) {
			// represents a destructuring assignment
			Expr.Tuple tv = (Expr.Tuple) lhs;
			ArrayList<Expr> tvFields = tv.fields;
			
			// FIXME: loss of nominal information here			
			Type rawRhs = rhs.result().raw();		
			Nominal.EffectiveTuple tupleRhs = resolver.expandAsEffectiveTuple(rhs.result());
			
			// FIXME: the following is something of a kludge. It would also be
			// nice to support more expressive destructuring assignment
			// operations.
			if(Type.isImplicitCoerciveSubtype(Type.T_REAL, rawRhs)) {
				tupleRhs = Nominal.Tuple(Nominal.T_INT,Nominal.T_INT);
			} else if(tupleRhs == null) {
				syntaxError("tuple value expected, got " + tupleRhs.nominal(),filename,rhs);
				return null; // deadcode
			} 
			
			List<Nominal> rhsElements = tupleRhs.elements();
			if(rhsElements.size() != tvFields.size()) {
				syntaxError("incompatible tuple assignment",filename,rhs);
			}			
			for(int i=0;i!=tvFields.size();++i) {
				Expr f = tvFields.get(i);
				Nominal t = rhsElements.get(i);
				
				if(f instanceof Expr.AbstractVariable) {
					Expr.AbstractVariable av = (Expr.AbstractVariable) f; 				
					Expr.AssignedVariable lv;
					if(lhs instanceof Expr.AssignedVariable) {
						// this case just avoids creating another object everytime we
						// visit this statement.
						lv = (Expr.AssignedVariable) lhs; 
					} else {
						lv = new Expr.AssignedVariable(av.var, av.attributes());
					}
					lv.type = Nominal.T_VOID;
					lv.afterType = t; 
					environment = environment.put(lv.var, t);					
					tvFields.set(i, lv);
				} else {
					syntaxError(errorMessage(INVALID_TUPLE_LVAL),filename,f);
				}								
			}										
		} else {	
			lhs = propagate(lhs,environment,typer);			
			Expr.AssignedVariable av = inferAfterType(lhs, rhs.result());
			environment = environment.put(av.var, av.afterType);
		}
		
		stmt.lhs = (Expr.LVal) lhs;
		stmt.rhs = rhs;	
		
		return environment;
	}
	
	private Expr.AssignedVariable inferAfterType(Expr.LVal lv,
			Nominal afterType) {
		if (lv instanceof Expr.AssignedVariable) {
			Expr.AssignedVariable v = (Expr.AssignedVariable) lv;			
			v.afterType = afterType;			
			return v;
		} else if (lv instanceof Expr.Dereference) {
			Expr.Dereference pa = (Expr.Dereference) lv;
			// NOTE: the before and after types are the same since an assignment
			// through a reference does not change its type.
			checkIsSubtype(pa.srcType,Nominal.Reference(afterType),lv);
			return inferAfterType((Expr.LVal) pa.src, pa.srcType);
		} else if (lv instanceof Expr.StringAccess) {
			Expr.StringAccess la = (Expr.StringAccess) lv;
			checkIsSubtype(Nominal.T_CHAR,afterType,lv);
			return inferAfterType((Expr.LVal) la.src, 
					Nominal.T_STRING);
		} else if (lv instanceof Expr.ListAccess) {
			Expr.ListAccess la = (Expr.ListAccess) lv;
			Nominal.EffectiveList srcType = la.srcType;
			afterType = (Nominal) srcType.update(afterType);								
			return inferAfterType((Expr.LVal) la.src, afterType);
		} else if(lv instanceof Expr.DictionaryAccess)  {
			Expr.DictionaryAccess da = (Expr.DictionaryAccess) lv;		
			Nominal.EffectiveDictionary srcType = da.srcType;
			afterType = (Nominal) srcType.update(da.index.result(),afterType);
			return inferAfterType((Expr.LVal) da.src, afterType);
		} else if(lv instanceof Expr.RecordAccess) {
			Expr.RecordAccess la = (Expr.RecordAccess) lv;
			Nominal.EffectiveRecord srcType = la.srcType;			
			// NOTE: I know I can modify this hash map, since it's created fresh
			// in Nominal.Record.fields().
			afterType = (Nominal) srcType.update(la.name, afterType);			
			return inferAfterType((Expr.LVal) la.src, afterType);
		} else {
			internalFailure("unknown lval: "
					+ lv.getClass().getName(), filename, lv);
			return null; //deadcode
		}
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Break stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {
		// FIXME: need to propagate environment to the break destination
		return BOTTOM;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Debug stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {
		stmt.expr = typer.propagate(stmt.expr,environment);				
		checkIsSubtype(Type.T_STRING,stmt.expr);
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.DoWhile stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {
								
		// Iterate to a fixed point
		RefCountedHashMap<String,Nominal> old = null;
		RefCountedHashMap<String,Nominal> tmp = null;
		RefCountedHashMap<String,Nominal> orig = environment.clone();
		boolean firstTime=true;
		do {
			old = environment.clone();
			if(!firstTime) {
				// don't do this on the first go around, to mimick how the
				// do-while loop works.
				tmp = typer.propagate(stmt.condition,true,old.clone()).second();
				environment = join(orig.clone(),propagate(stmt.body,tmp,typer));
			} else {
				firstTime=false;
				environment = join(orig.clone(),propagate(stmt.body,old,typer));
			}					
			old.free(); // hacky, but safe
		} while(!environment.equals(old));

		if (stmt.invariant != null) {
			stmt.invariant = typer.propagate(stmt.invariant, environment);
			checkIsSubtype(Type.T_BOOL,stmt.invariant);
		}		

		Pair<Expr,RefCountedHashMap<String,Nominal>> p = typer.propagate(stmt.condition,false,environment);
		stmt.condition = p.first();
		environment = p.second();
		
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.ForAll stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) throws ResolveError {
		
		stmt.source = typer.propagate(stmt.source,environment);
		Type rawType = stmt.source.result().raw(); 		
		
		// At this point, the major task is to determine what the types for the
		// iteration variables declared in the for loop. More than one variable
		// is permitted in some cases.
		
		Nominal[] elementTypes = new Nominal[stmt.variables.size()];		
		if(Type.isSubtype(Type.List(Type.T_ANY, false),rawType)) {			
			Nominal.EffectiveList lt = resolver.expandAsEffectiveList(stmt.source.result());
			if(elementTypes.length == 1) {
				elementTypes[0] = lt.element();
			} else {
				syntaxError(errorMessage(VARIABLE_POSSIBLY_UNITIALISED),filename,stmt);
			}			
		} else if(Type.isSubtype(Type.Set(Type.T_ANY, false),rawType)) {
			Nominal.EffectiveSet st = resolver.expandAsEffectiveSet(stmt.source.result());
			if(elementTypes.length == 1) {
				elementTypes[0] = st.element();
			} else {
				syntaxError(errorMessage(VARIABLE_POSSIBLY_UNITIALISED),filename,stmt);
			}					
		} else if(Type.isSubtype(Type.Dictionary(Type.T_ANY, Type.T_ANY),rawType)) {
			Nominal.EffectiveDictionary dt = resolver.expandAsEffectiveDictionary(stmt.source.result());
			if(elementTypes.length == 1) {
				elementTypes[0] = Nominal.Tuple(dt.key(),dt.value());			
			} else if(elementTypes.length == 2) {					
				elementTypes[0] = dt.key();
				elementTypes[1] = dt.value();
			} else {
				syntaxError(errorMessage(VARIABLE_POSSIBLY_UNITIALISED),filename,stmt);
			}
			
		} else if(Type.isSubtype(Type.T_STRING,rawType)) {
			if(elementTypes.length == 1) {
				elementTypes[0] = Nominal.T_CHAR;
			} else {
				syntaxError(errorMessage(VARIABLE_POSSIBLY_UNITIALISED),filename,stmt);
			}				
		} else {
			syntaxError(errorMessage(INVALID_SET_OR_LIST_EXPRESSION),filename,stmt);
			return null; // deadcode
		}
		
		// Now, update the environment to include those declared variables
		ArrayList<String> stmtVariables = stmt.variables;
		for(int i=0;i!=elementTypes.length;++i) {
			String var = stmtVariables.get(i);
			if (environment.containsKey(var)) {
				syntaxError(errorMessage(VARIABLE_ALREADY_DEFINED,var),
						filename, stmt);
			}			
			environment = environment.put(var, elementTypes[i]);
		} 
				
		// Iterate to a fixed point
		RefCountedHashMap<String,Nominal> old = null;
		RefCountedHashMap<String,Nominal> orig = environment.clone();
		do {
			old = environment.clone();
			environment = join(orig.clone(),propagate(stmt.body,old,typer));
			old.free(); // hacky, but safe
		} while(!environment.equals(old));
		
		// Remove loop variables from the environment, since they are only
		// declared for the duration of the body but not beyond.
		for(int i=0;i!=elementTypes.length;++i) {
			String var = stmtVariables.get(i);				
			environment = environment.remove(var);
		} 
		
		if (stmt.invariant != null) {
			stmt.invariant = typer.propagate(stmt.invariant, environment);
			checkIsSubtype(Type.T_BOOL,stmt.invariant);
		}
				
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.IfElse stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {
		
		// First, check condition and apply variable retypings.
		Pair<Expr,RefCountedHashMap<String,Nominal>> p1,p2;
		
		p1 = typer.propagate(stmt.condition,true,environment.clone());
		p2 = typer.propagate(stmt.condition,false,environment);
		stmt.condition = p1.first();
		
		RefCountedHashMap<String,Nominal> trueEnvironment = p1.second();
		RefCountedHashMap<String,Nominal> falseEnvironment = p2.second();
				
		// Second, update environments for true and false branches
		if(stmt.trueBranch != null && stmt.falseBranch != null) {
			trueEnvironment = propagate(stmt.trueBranch,trueEnvironment,typer);
			falseEnvironment = propagate(stmt.falseBranch,falseEnvironment,typer);						
		} else if(stmt.trueBranch != null) {			
			trueEnvironment = propagate(stmt.trueBranch,trueEnvironment,typer);
		} else if(stmt.falseBranch != null){								
			trueEnvironment = environment;
			falseEnvironment = propagate(stmt.falseBranch,falseEnvironment,typer);		
		} 
		
		// Finally, join results back together		
		return join(trueEnvironment,falseEnvironment);							
	}
	
	private RefCountedHashMap<String, Nominal> propagate(
			Stmt.Return stmt,
			RefCountedHashMap<String, Nominal> environment,
			ExpressionTyper typer) throws ResolveError {
		
		if (stmt.expr != null) {
			stmt.expr = typer.propagate(stmt.expr, environment);
			Nominal rhs = stmt.expr.result();
			checkIsSubtype(current.resolvedType().ret(),rhs, stmt.expr);
		}	
		
		environment.free();
		return BOTTOM;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Skip stmt,
			RefCountedHashMap<String,Nominal> environment,
			ArrayList<WhileyFile.Import> imports) {		
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Switch stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) throws ResolveError {
		
		stmt.expr = typer.propagate(stmt.expr,environment);		
		
		RefCountedHashMap<String,Nominal> finalEnv = null;
		boolean hasDefault = false;
		
		for(Stmt.Case c : stmt.cases) {
			
			// first, resolve the constants
			
			ArrayList<Value> values = new ArrayList<Value>();
			for(Expr e : c.expr) {
				values.add(resolver.resolveAsConstant(e,typer.context()));				
			}
			c.constants = values;

			// second, propagate through the statements
			
			RefCountedHashMap<String,Nominal> localEnv = environment.clone();
			localEnv = propagate(c.stmts,localEnv,typer);
			
			if(finalEnv == null) {
				finalEnv = localEnv;
			} else {
				finalEnv = join(finalEnv,localEnv);
			} 
			
			// third, keep track of whether a default
			hasDefault |= c.expr.isEmpty();
		}
		
		if(!hasDefault) {
			
			// in this case, there is no default case in the switch. We must
			// therefore assume that there are values which will fall right
			// through the switch statement without hitting a case. Therefore,
			// we must include the original environment to accound for this. 
			
			finalEnv = join(finalEnv,environment);
		} else {
			environment.free();
		}
		
		return finalEnv;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.Throw stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {
		stmt.expr = typer.propagate(stmt.expr,environment);
		return BOTTOM;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.TryCatch stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) throws ResolveError {
		

		for(Stmt.Catch handler : stmt.catches) {
			
			// FIXME: need to deal with handler environments properly!
			try {
				Nominal type = resolver.resolveAsType(handler.unresolvedType, typer.context()); 
				handler.type = type;
				RefCountedHashMap<String,Nominal> local = environment.clone();
				local = local.put(handler.variable, type);									
				propagate(handler.stmts,local,typer);
				local.free();
			} catch(ResolveError e) {
				syntaxError(errorMessage(RESOLUTION_ERROR,e.getMessage()),filename,handler,e);
			} catch(SyntaxError e) {
				throw e;
			} catch(Throwable t) {
				internalFailure(t.getMessage(),filename,handler,t);
			}
		}
		
		environment = propagate(stmt.body,environment,typer);
				
		// need to do handlers here
		
		return environment;
	}
	
	private RefCountedHashMap<String,Nominal> propagate(Stmt.While stmt,
			RefCountedHashMap<String,Nominal> environment,
			ExpressionTyper typer) {

		// Iterate to a fixed point
		RefCountedHashMap<String,Nominal> old = null;
		RefCountedHashMap<String,Nominal> tmp = null;
		RefCountedHashMap<String,Nominal> orig = environment.clone();
		do {
			old = environment.clone();
			tmp = typer.propagate(stmt.condition,true,old.clone()).second();			
			environment = join(orig.clone(),propagate(stmt.body,tmp,typer));			
			old.free(); // hacky, but safe
		} while(!environment.equals(old));
		
		if (stmt.invariant != null) {
			stmt.invariant = typer.propagate(stmt.invariant, environment);
			checkIsSubtype(Type.T_BOOL,stmt.invariant);
		}		
				
		Pair<Expr,RefCountedHashMap<String,Nominal>> p = typer.propagate(stmt.condition,false,environment);
		stmt.condition = p.first();
		environment = p.second();			
		
		return environment;
	}
	
	private Expr.LVal propagate(Expr.LVal lval,
			RefCountedHashMap<String, Nominal> environment,
			ExpressionTyper typer) {
		try {
			if(lval instanceof Expr.AbstractVariable) {
				Expr.AbstractVariable av = (Expr.AbstractVariable) lval;
				Nominal p = environment.get(av.var);
				if(p == null) {
					syntaxError(errorMessage(UNKNOWN_VARIABLE),filename,lval);
				}				
				Expr.AssignedVariable lv = new Expr.AssignedVariable(av.var, av.attributes());
				lv.type = p;				
				return lv;
			} else if(lval instanceof Expr.Dereference) {
				Expr.Dereference pa = (Expr.Dereference) lval;
				Expr.LVal src = propagate((Expr.LVal) pa.src,environment,typer);												
				pa.src = src;
				pa.srcType = resolver.expandAsReference(src.result());							
				return pa;
			} else if(lval instanceof Expr.AbstractIndexAccess) {
				// this indicates either a list, string or dictionary update
				Expr.AbstractIndexAccess ai = (Expr.AbstractIndexAccess) lval;				
				Expr.LVal src = propagate((Expr.LVal) ai.src,environment,typer);				
				Expr index = typer.propagate(ai.index,environment);				
				Type rawSrcType = src.result().raw();
				// FIXME: problem if list is only an effective list, similarly
				// for dictionaries.
				if(Type.isSubtype(Type.T_STRING, rawSrcType)) {
					return new Expr.StringAccess(src,index,lval.attributes());
				} else if(Type.isSubtype(Type.List(Type.T_ANY,false), rawSrcType)) {
					Expr.ListAccess la = new Expr.ListAccess(src,index,lval.attributes());
					la.srcType = resolver.expandAsEffectiveList(src.result()); 			
					return la;
				} else  if(Type.isSubtype(Type.Dictionary(Type.T_ANY, Type.T_ANY), rawSrcType)) {
					Expr.DictionaryAccess da = new Expr.DictionaryAccess(src,index,lval.attributes());
					da.srcType = resolver.expandAsEffectiveDictionary(src.result());										
					return da;
				} else {				
					syntaxError(errorMessage(INVALID_LVAL_EXPRESSION),filename,lval);
				}
			} else if(lval instanceof Expr.AbstractDotAccess) {
				// this indicates a record update
				Expr.AbstractDotAccess ad = (Expr.AbstractDotAccess) lval;
				Expr.LVal src = propagate((Expr.LVal) ad.src,environment,typer);
				Expr.RecordAccess ra = new Expr.RecordAccess(src, ad.name, ad.attributes());
				Nominal.EffectiveRecord srcType = resolver.expandAsEffectiveRecord(src.result());
				if(srcType == null) {								
					syntaxError(errorMessage(INVALID_LVAL_EXPRESSION),filename,lval);					
				} else if(srcType.field(ra.name) == null) {
					syntaxError(errorMessage(RECORD_MISSING_FIELD),filename,lval);
				}
				ra.srcType = srcType;
				return ra;
			}
		} catch(SyntaxError e) {
			throw e;
		} catch(Throwable e) {
			internalFailure(e.getMessage(),filename,lval,e);
			return null; // dead code
		}		
		internalFailure("unknown lval: " + lval.getClass().getName(),filename,lval);
		return null; // dead code
	}		
	
	private <T extends Type> T checkType(Type t, Class<T> clazz,
			SyntacticElement elem) {
		if (clazz.isInstance(t)) {
			return (T) t;
		} else {
			syntaxError(errorMessage(SUBTYPE_ERROR, clazz.getName().replace('$', '.'), t),
					filename, elem);
			return null;
		}
	}
	
	// Check t1 :> t2
	private void checkIsSubtype(Nominal t1, Nominal t2,
			SyntacticElement elem) {
		if (!Type.isImplicitCoerciveSubtype(t1.raw(), t2.raw())) {
			syntaxError(
					errorMessage(SUBTYPE_ERROR, t1.nominal(), t2.nominal()),
					filename, elem);
		}
	}	
	
	private void checkIsSubtype(Nominal t1, Expr t2) {
		if (!Type.isImplicitCoerciveSubtype(t1.raw(), t2.result().raw())) {
			// We use the nominal type for error reporting, since this includes
			// more helpful names.
			syntaxError(
					errorMessage(SUBTYPE_ERROR, t1.nominal(), t2.result()
							.nominal()), filename, t2);
		}
	}
	
	private void checkIsSubtype(Type t1, Expr t2) {
		if (!Type.isImplicitCoerciveSubtype(t1, t2.result().raw())) {
			// We use the nominal type for error reporting, since this includes
			// more helpful names.
			syntaxError(errorMessage(SUBTYPE_ERROR, t1, t2.result().nominal()),
					filename, t2);
		}
	}
	
	/**
	 * The purpose of the exposed names method is capture the case when we have
	 * a define statement like this:
	 * 
	 * <pre>
	 * define tup as {int x, int y} where x < y
	 * </pre>
	 * 
	 * In this case, <code>x</code> and <code>y</code> are "exposed" --- meaning
	 * their real names are different in some way. In this case, the aliases we
	 * have are: x->$.x and y->$.y
	 * 
	 * @param src
	 * @param t
	 * @param environment
	 */
	private static void addExposedNames(Expr src, UnresolvedType t,
			HashMap<String, Set<Expr>> environment) {
		// Extended this method to handle lists and sets etc, is very difficult.
		// The primary problem is that we need to expand expressions involved
		// names exposed in this way into quantified
		// expressions.		
		if(t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			for(Map.Entry<String,UnresolvedType> e : tt.types.entrySet()) {
				Expr s = new Expr.RecordAccess(src, e
						.getKey(), src.attribute(Attribute.Source.class));
				addExposedNames(s,e.getValue(),environment);
				Set<Expr> aliases = environment.get(e.getKey());
				if(aliases == null) {
					aliases = new HashSet<Expr>();
					environment.put(e.getKey(),aliases);
				}
				aliases.add(s);
			}
		} else if (t instanceof UnresolvedType.Reference) {			
			UnresolvedType.Reference ut = (UnresolvedType.Reference) t;
			addExposedNames(new Expr.Dereference(src),
					ut.element, environment);
		}
	}
	
	private abstract static class Scope {
		public abstract void free();
	}
	
	private static final class Handler {
		public final Type exception;
		public final String variable;
		public RefCountedHashMap<String,Nominal> environment;
		
		public Handler(Type exception, String variable) {
			this.exception = exception;
			this.variable = variable;
			this.environment = new RefCountedHashMap<String,Nominal>();
		}
	}
	
	private static final class TryCatchScope extends Scope {
		public final ArrayList<Handler> handlers = new ArrayList<Handler>();
						
		public void free() {
			for(Handler handler : handlers) {
				handler.environment.free();
			}
		}
	}
	
	private static final class BreakScope extends Scope {
		public RefCountedHashMap<String,Nominal> environment;
		
		public void free() {
			environment.free();
		}
	}

	private static final class ContinueScope extends Scope {
		public RefCountedHashMap<String,Nominal> environment;
		
		public void free() {
			environment.free();
		}
	}
	
	private static final RefCountedHashMap<String,Nominal> BOTTOM = new RefCountedHashMap<String,Nominal>();
	
	private static final RefCountedHashMap<String, Nominal> join(
			RefCountedHashMap<String, Nominal> lhs,
			RefCountedHashMap<String, Nominal> rhs) {
		
		// first, need to check for the special bottom value case.
		
		if(lhs == BOTTOM) {
			return rhs;
		} else if(rhs == BOTTOM) {
			return lhs;
		}
		
		// ok, not bottom so compute intersection.
		
		lhs.free();
		rhs.free(); 		
		
		RefCountedHashMap<String,Nominal> result = new RefCountedHashMap<String,Nominal>();
		for(String key : lhs.keySet()) {
			if(rhs.containsKey(key)) {
				Nominal lhs_t = lhs.get(key);
				Nominal rhs_t = rhs.get(key);				
				result.put(key, Nominal.Union(lhs_t, rhs_t));
			}
		}
		
		return result;
	}	
}
