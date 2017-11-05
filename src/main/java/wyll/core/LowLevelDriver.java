package wyll.core;

import static wyc.lang.WhileyFile.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Tuple;
import wybs.util.AbstractCompilationUnit.Value;
import wyc.lang.WhileyFile;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Expr;
import wyc.lang.WhileyFile.LVal;
import wyc.lang.WhileyFile.Stmt;
import wyc.lang.WhileyFile.Type;
import wyc.util.AbstractVisitor;
import wycc.util.Pair;
import wyil.type.TypeSystem;
import wyll.task.TypeMangler;
import wyll.util.StdTypeMangler;

public class LowLevelDriver<D, S, E extends S> {
	private final TypeSystem typeSystem;
	private final TypeMangler mangler;
	private final LowLevel.Visitor<D, S, E> visitor;
	/**
	 * The auxillaries are the list of additional declarations created as
	 * intermediates during the translation process.
	 */
	private final ArrayList<D> auxillaries = new ArrayList<>();

	public LowLevelDriver(TypeSystem typeSystem, LowLevel.Visitor<D, S, E> visitor) {
		this.typeSystem = typeSystem;
		this.mangler = new StdTypeMangler();
		this.visitor = visitor;
	}

	public List<D> visitWhileyFile(WhileyFile wf) {
		auxillaries.clear();
		ArrayList<D> declarations = new ArrayList<>();
		for (Decl decl : wf.getDeclarations()) {
			D d = visitDeclaration(decl);
			if (d != null) {
				declarations.add(d);
			}
		}
		declarations.addAll(auxillaries);
		return declarations;
	}

	// ==========================================================================
	// Declarations
	// ==========================================================================

	public D visitDeclaration(Decl decl) {
		switch (decl.getOpcode()) {
		case DECL_importfrom:
		case DECL_import:
			return visitImport((Decl.Import) decl);
		case DECL_staticvar:
			return visitStaticVariable((Decl.StaticVariable) decl);
		case DECL_type:
		case DECL_rectype:
			return visitType((Decl.Type) decl);
		case DECL_function:
		case DECL_method:
		case DECL_property:
			return visitCallable((Decl.Callable) decl);
		default:
			throw new IllegalArgumentException("unknown declaration encountered (" + decl.getClass().getName() + ")");
		}
	}

	public D visitImport(Decl.Import decl) {
		return null;
	}

	public D visitStaticVariable(Decl.StaticVariable decl) {
		E initialiser = null;
		LowLevel.Type type = visitType(decl.getType());
		if (decl.hasInitialiser()) {
			initialiser = visitExpression(decl.getInitialiser(), decl.getType());
		}
		return visitor.visitStaticVariable(decl.getName().toString(), type, initialiser);
	}

	public D visitType(Decl.Type decl) {
		LowLevel.Type type = visitType(decl.getVariableDeclaration().getType());
		if(decl.getInvariant().size() > 0) {
			auxillaries.add(createInvariantMethod(decl));
		}
		return visitor.visitType(decl.getName().toString(), type);
	}

	public D createInvariantMethod(Decl.Type decl) {
		Tuple<Expr> invariant = decl.getInvariant();
		String name = decl.getName().toString() + "$inv";
		LowLevel.Type paramT = visitType(decl.getType());
		LowLevel.Type varT = visitBool(Type.Bool);
		ArrayList<S> body = new ArrayList<>();
		if (invariant.size() == 1) {
			// Simple case: just evaluate and return invariant
			body.add(visitor.visitReturn(visitExpression(invariant.get(0), Type.Bool)));
		} else {
			// Complex case: evaluate each clause in turn
			String var = createTemporaryVariable(decl.getIndex());
			body.add(visitor.visitVariableDeclaration(varT, var, visitor.visitLogicalInitialiser(true)));
			for (int i = 0; i != invariant.size(); ++i) {
				E lhs = visitor.visitVariableAccess(varT, var);
				E rhs = visitExpression(invariant.get(i), Type.Bool);
				rhs = visitor.visitLogicalAnd(lhs, rhs);
				body.add(visitor.visitAssign(lhs, rhs));
			}
			body.add(visitor.visitReturn(visitor.visitVariableAccess(varT, var)));
		}
		ArrayList<wycc.util.Pair<LowLevel.Type, String>> parameters = new ArrayList<>();
		parameters.add(new wycc.util.Pair<>(paramT, decl.getVariableDeclaration().getName().toString()));
		return visitor.visitMethod(name, parameters, varT, body);
	}

	public D visitCallable(Decl.Callable decl) {
		// Determine appropriate name mangle
		String name = getMangledName(decl);
		// Construct parameter list
		Tuple<Decl.Variable> parameters = decl.getParameters();
		ArrayList<wycc.util.Pair<LowLevel.Type, String>> nParameters = new ArrayList<>();
		for (int i = 0; i != parameters.size(); ++i) {
			Decl.Variable parameter = parameters.get(i);
			LowLevel.Type parameterType = visitType(parameter.getType());
			nParameters.add(new wycc.util.Pair<>(parameterType, parameter.getName().toString()));
		}
		// Determine appropriate return type which properly encodes multiple returns
		// when present. If no return value is given then this is null.
		LowLevel.Type retType = visitType(getMultipleReturnType(decl.getType().getReturns()));
		// Construct function or method body
		if (decl instanceof Decl.FunctionOrMethod) {
			Decl.FunctionOrMethod fm = (Decl.FunctionOrMethod) decl;
			// Done!!
			List<S> body = visitStatement(fm.getBody());
			return visitor.visitMethod(name, nParameters, retType, body);
		} else {
			// FIXME: need to construct appropriate body from where clause
			throw new UnsupportedOperationException();
		}
	}

	// ==========================================================================
	// Statements
	// ==========================================================================

	public List<S> visitStatement(Stmt stmt) {
		switch (stmt.getOpcode()) {
		case STMT_assign:
			return visitAssign((Stmt.Assign) stmt);
		case STMT_block:
			return visitBlock((Stmt.Block) stmt);
		case STMT_namedblock:
			return visitNamedBlock((Stmt.NamedBlock) stmt);
		default: {
			S s = visitUnitStatement(stmt);
			if (s == null) {
				return Collections.EMPTY_LIST;
			} else {
				ArrayList<S> list = new ArrayList<>();
				list.add(s);
				return list;
			}
		}
		}
	}

	/**
	 * A unit statement is simply one whose translation is guaranteed to produce at
	 * most a single statement.
	 *
	 * @param stmt
	 * @return
	 */
	public S visitUnitStatement(Stmt stmt) {
		switch (stmt.getOpcode()) {
		case DECL_variable:
		case DECL_variableinitialiser:
			return visitVariable((Decl.Variable) stmt);
		case STMT_assert:
			return visitAssert((Stmt.Assert) stmt);
		case STMT_assume:
			return visitAssume((Stmt.Assume) stmt);
		case STMT_break:
			return visitBreak((Stmt.Break) stmt);
		case STMT_continue:
			return visitContinue((Stmt.Continue) stmt);
		case STMT_debug:
			return visitDebug((Stmt.Debug) stmt);
		case STMT_dowhile:
			return visitDoWhile((Stmt.DoWhile) stmt);
		case STMT_fail:
			return visitFail((Stmt.Fail) stmt);
		case STMT_if:
		case STMT_ifelse:
			return visitIfElse((Stmt.IfElse) stmt);
		case EXPR_invoke: {
			Expr.Invoke ivk = (Expr.Invoke) stmt;
			return visitInvoke(ivk, ivk.getType());
		}
		case EXPR_indirectinvoke: {
			Expr.IndirectInvoke ivk = (Expr.IndirectInvoke) stmt;
			return visitIndirectInvoke(ivk, ivk.getType());
		}
		case STMT_return:
			return visitReturn((Stmt.Return) stmt);
		case STMT_skip:
			return visitSkip((Stmt.Skip) stmt);
		case STMT_switch:
			return visitSwitch((Stmt.Switch) stmt);
		case STMT_while:
			return visitWhile((Stmt.While) stmt);
		default:
			throw new IllegalArgumentException("unknown statement encountered (" + stmt.getClass().getName() + ")");
		}
	}

	public S visitVariable(Decl.Variable stmt) {
		LowLevel.Type type = visitType(stmt.getType());
		E initialiser = null;
		if (stmt.hasInitialiser()) {
			initialiser = visitExpression(stmt.getInitialiser(), stmt.getType());
		}
		return visitor.visitVariableDeclaration(type, stmt.getName().toString(), initialiser);
	}

	public S visitAssert(Stmt.Assert stmt) {
		E condition = visitExpression(stmt.getCondition(), Type.Bool);
		return visitor.visitAssert(condition);
	}

	public S visitAssume(Stmt.Assume stmt) {
		E condition = visitExpression(stmt.getCondition(), Type.Bool);
		return visitor.visitAssert(condition);
	}

	public List<S> visitAssign(Stmt.Assign stmt) {
		// Check whether or not we have to resort to a more complex translation of the
		// assignment statement.
		if (hasMultipleExpression(stmt) || hasInterference(stmt)) {
			return translateComplexAssign(stmt);
		} else {
			return translateSimpleAssign(stmt);
		}
	}

	/**
	 * Translate a "simple statement". This can be a multiple assignment, but it
	 * cannot have a multiple expression on the right-hand side *or* any
	 * interference. Thus, it is translated directly as a sequence of simple
	 * assignments. For example:
	 *
	 * <pre>
	 * x,y = 1,a+2
	 * </pre>
	 *
	 * This is translated as the following sequence of assignments:
	 *
	 * <pre>
	 * x = 1
	 * y = a+2
	 * </pre>
	 *
	 * Such a translation offers the simplest and cleanest representation of the
	 * original code. But, it is only safe with the right conditions.
	 */
	public List<S> translateSimpleAssign(Stmt.Assign stmt) {
		// ASSERT: |lvals| == |rvals|
		Tuple<LVal> lvals = stmt.getLeftHandSide();
		Tuple<Expr> rvals = stmt.getRightHandSide();
		ArrayList<S> stmts = new ArrayList<>();
		//
		for (int i = 0; i != lvals.size(); ++i) {
			LVal lval = lvals.get(i);
			E lhs = visitExpression(lval, lval.getType());
			E rhs = visitExpression(rvals.get(i), lval.getType());
			stmts.add(visitor.visitAssign(lhs, rhs));
		}
		//
		return stmts;
	}

	/**
	 * <p>
	 * Translate a complex assignment. This is multiple assignment which either
	 * contains a multiple expression on the right-hand side or has some form of
	 * assignment interference. For example:
	 * </p>
	 *
	 * <pre>
	 * function swap(int x, int y) -> (int a, int b):
	 *    return y, x
	 *
	 * ...
	 *
	 * c,d = swap(c,d)
	 * </pre>
	 *
	 * <p>
	 * This illustrates a multiple expression on the right hand side of the
	 * assignment. The following illustrates interference:
	 * </p>
	 *
	 * <pre>
	 * x,y = y,x
	 * </pre>
	 *
	 * <p>
	 * The problem here is that the assignment to <code>x</code> on the left-hand
	 * side interferes with its read on the right-hand side (i.e. read-after-write
	 * interference). This issue of interference is resolved through the use of
	 * temporary variables. For example, we might translate the above multiple
	 * assignment as follows:
	 * </p>
	 *
	 * <pre>
	 * int tmp1 = y
	 * int tmp2 = x
	 * x = tmp1
	 * y = tmp2
	 * </pre>
	 *
	 * <p>
	 * This is a little more ugly, but has the obvious advantage of being correct.
	 * To handle multiple returns from functions, we wrap them in records as
	 * follows:
	 * </p>
	 *
	 * <pre>
	 * function f(int x) -> (int a, int b):
	 *    ...
	 *
	 * ...
	 * x,y = f(0)
	 * </pre>
	 *
	 * <p>
	 * Since we assume the underlying platform cannot handle a multiple assignment
	 * like this, we translate the above into something like this:
	 * </p>
	 *
	 * <pre>
	 * function f(int x) -> {int a, int b}:
	 *    ...
	 *
	 * ...
	 * {int a, int b} tmp = f(0)
	 * x = tmp.a
	 * y = tmp.b
	 * </pre>
	 *
	 * <p>
	 * Again, this translation is a little ugly but it basically works quite well.
	 * </p>
	 *
	 * @param stmt
	 * @param context
	 * @return
	 */
	public List<S> translateComplexAssign(Stmt.Assign stmt) {
		Tuple<LVal> lhs = stmt.getLeftHandSide();
		Tuple<Expr> rhs = stmt.getRightHandSide();
		ArrayList<S> stmts = new ArrayList<>();
		Type[] temporaryTypes = new Type[rhs.size()];
		String[] temporaryVars = new String[rhs.size()];
		// Declare temporary variables with appropriate types and initialise them with
		// the rvals of this assignment. For the case of rvals with multiple returns,
		// generate the appropriate wrapper type.
		for (int i = 0; i != rhs.size(); ++i) {
			Expr rval = rhs.get(i);
			E initialiser;
			// Create a temporary variable name. This should not clash with any other
			// variables in the given scope.
			temporaryVars[i] = createTemporaryVariable(rval.getIndex());
			// Determine appropriate type for variable and translate initialiser.
			if (rval.getTypes() == null) {
				temporaryTypes[i] = rval.getType();
				initialiser = visitExpression(rval, rval.getType());
			} else {
				temporaryTypes[i] = getMultipleReturnType(rval.getTypes());
				initialiser = visitMultipleExpression(rval, rval.getTypes());
			}
			LowLevel.Type temporaryType = visitType(temporaryTypes[i]);
			stmts.add(visitor.visitVariableDeclaration(temporaryType, temporaryVars[i], initialiser));
		}
		// For each lval create an appropriate assignment. We need take care here to
		// ensure that, in the case of a multiple return, we extract the correct field
		// from the temporaries wrapper (i.e. record) type.
		for (int i = 0, j = 0; i != rhs.size(); ++i) {
			Expr rv = rhs.get(i);
			Tuple<Type> rhsTypes = rv.getTypes();
			String temporaryVar = temporaryVars[i];
			Type temporaryType = temporaryTypes[i];
			LowLevel.Type llTemporaryType = visitType(temporaryType);
			if (rhsTypes == null) {
				// Easy case for single assignments
				Expr lv = lhs.get(j++);
				E lval = visitExpression(lv, lv.getType());
				E rval = visitor.visitVariableAccess(llTemporaryType, temporaryVar);
				// Apply any coercions required of the assignment.
				rval = applyCoercion(lv.getType(), temporaryType, rval);
				stmts.add(visitor.visitAssign(lval, rval));
			} else {
				// Harder case for multiple assignments. First, store return value into
				// temporary register. Then load from that. At this time, the only way to have a
				// multiple return is via some kind of invocation.
				LowLevel.Type.Record llRecT = (LowLevel.Type.Record) llTemporaryType;
				for (int k = 0; k != rhsTypes.size(); ++k) {
					Expr lv = lhs.get(j++);
					E lval = visitExpression(lv, lv.getType());
					E rval = visitor.visitVariableAccess(llTemporaryType, temporaryVar);
					rval = visitor.visitRecordAccess(llRecT, rval, "f" + k);
					// Apply any coercions required of the assignment.
					rval = applyCoercion(lv.getType(), rhsTypes.get(k), rval);
					stmts.add(visitor.visitAssign(lval, rval));
				}
			}
		}
		// Done.
		return stmts;
	}

	/**
	 * Check whether a given assignment has a multiple expression on the right-hand
	 * side. This amounts to checking whether or not the number of expressions on
	 * the right-hand size matches the number of lvals on the left-hand side. This
	 * is necessary to determine when we need to handle multiple assignments.
	 *
	 * @param stmt
	 * @return
	 */
	public boolean hasMultipleExpression(Stmt.Assign stmt) {
		return stmt.getLeftHandSide().size() != stmt.getRightHandSide().size();
	}

	/**
	 * Check whether or not a variable modified by an assignment will interfere with
	 * an expression on the right-hand side of the assignment. The following
	 * illustrates this:
	 *
	 * <pre>
	 * function swap(int x, int y) -> (int a, int b):
	 *    x,y = y,x
	 *    return x,y
	 * </pre>
	 *
	 * The interference comes in the assignment. We can see it more clearly if we
	 * attempt to naively translate this multiple assignment into a series of simple
	 * assignments:
	 *
	 * <pre>
	 *   x = y
	 *   y = x
	 * </pre>
	 *
	 * This obviously does not achieve the intended aim, since the original value of
	 * <code>x</code> is lost. Thus, the assignment to <code>x</code> is said
	 * "interfere" with the assignment to <code>y<code>.
	 *
	 * @param stmt
	 * @return
	 */
	public boolean hasInterference(Stmt.Assign stmt) {
		Tuple<LVal> lhs = stmt.getLeftHandSide();
		Tuple<Expr> rhs = stmt.getRightHandSide();
		for (int i = 0; i != lhs.size(); ++i) {
			Decl.Variable lval = extractVariable(lhs.get(i));
			if (lval != null) {
				for (int j = (i + 1); j != rhs.size(); ++j) {
					// FIXME: this loop could be optimised as there are situations with interference
					// between lhs and rhs which are not actual instances of interference.
					if (hasInterference(lval, rhs.get(j))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Check whether a given rhs expression "interferes" with a given lhs variable.
	 * That is, whether or not it uses the variable.
	 *
	 * @param lval
	 * @param rhs
	 * @return
	 */
	public boolean hasInterference(Decl.Variable lval, Expr rhs) {
		HashSet<Decl.Variable> uses = new HashSet<>();
		AbstractVisitor visitor = new AbstractVisitor() {
			@Override
			public void visitVariableAccess(Expr.VariableAccess var) {
				uses.add(var.getVariableDeclaration());
			}
		};
		visitor.visitExpression(rhs);
		return uses.contains(lval);
	}

	public Decl.Variable extractVariable(LVal lval) {
		switch (lval.getOpcode()) {
		case EXPR_variablecopy:
		case EXPR_variablemove: {
			Expr.VariableAccess va = (Expr.VariableAccess) lval;
			return va.getVariableDeclaration();
		}
		case EXPR_arrayaccess:
		case EXPR_arrayborrow: {
			Expr.ArrayAccess aa = (Expr.ArrayAccess) lval;
			return extractVariable((LVal) aa.getFirstOperand());
		}
		case EXPR_recordaccess:
		case EXPR_recordborrow: {
			Expr.RecordAccess aa = (Expr.RecordAccess) lval;
			return extractVariable((LVal) aa.getOperand());
		}
		case EXPR_dereference:
			return null;
		default:
			throw new IllegalArgumentException("unknown lval encountered (" + lval.getClass().getName() + ")");
		}
	}

	public List<S> visitBlock(Stmt.Block stmt) {
		ArrayList<S> block = new ArrayList<>();
		for (int i = 0; i != stmt.size(); ++i) {
			block.addAll(visitStatement(stmt.get(i)));
		}
		return block;
	}

	public S visitBreak(Stmt.Break stmt) {
		return visitor.visitBreak();
	}

	public S visitContinue(Stmt.Continue stmt) {
		return visitor.visitContinue();
	}

	public S visitDebug(Stmt.Debug stmt) {
		return null;
	}

	/**
	 * Translation of DoWhile statements is relatively straightforward. The only
	 * interesting issue is how loop invariants are checked in debug mode.
	 *
	 * @param stmt
	 * @return
	 */
	public S visitDoWhile(Stmt.DoWhile stmt) {
		E condition = visitExpression(stmt.getCondition(), Type.Bool);
		Stmt.Block block = stmt.getBody();
		ArrayList<S> body = new ArrayList<>();
		for (int i = 0; i != block.size(); ++i) {
			body.addAll(visitStatement(block.get(i)));
		}
		return visitor.visitDoWhile(condition, body);
	}

	public S visitFail(Stmt.Fail stmt) {
		return null;
	}

	public S visitIfElse(Stmt.IfElse stmt) {
		List<wycc.util.Pair<E, List<S>>> branches = new ArrayList<>();
		// First, translate true branch
		E condition = visitExpression(stmt.getCondition(), Type.Bool);
		List<S> trueBranch = visitBlock(stmt.getTrueBranch());
		branches.add(new wycc.util.Pair<>(condition, trueBranch));
		// Second, translate false branch (if applicable)
		if (stmt.hasFalseBranch()) {
			List<S> falseBranch = visitBlock(stmt.getFalseBranch());
			branches.add(new wycc.util.Pair<>(null, falseBranch));
		}
		// Done
		return visitor.visitIfElse(branches);
	}

	public List<S> visitNamedBlock(Stmt.NamedBlock stmt) {
		return null;
	}

	/**
	 * Translating a return statement is straightforward in most cases. The main
	 * difficulty arises (as usual) from the potential for multiple return values.
	 * There are two main cases: multiple returns, and multiple return values. For
	 * example:
	 *
	 * <pre>
	 * function swap(int x, int y) -> (int a, int b):
	 *    return y,x
	 * </pre>
	 *
	 * This is a relatively straightforward example of a return statement with
	 * multiple returns. It will be translated into something like the following:
	 *
	 * <pre>
	 * function swap(int x, int y) -> {int a, int b}:
	 *    return {a:y,b:x}
	 * </pre>
	 *
	 * The more complicate case arises from a return value which itself has multiple
	 * returns. The following illustrates:
	 *
	 * <pre>
	 *   ...
	 *   return swap(1,2)
	 * </pre>
	 *
	 * Here, we have a more complex situation. The only real way to resolve this is
	 * by storing the result in a temporary variable.
	 *
	 * @param stmt
	 * @return
	 */
	public S visitReturn(Stmt.Return stmt) {
		// FIXME: this will be broken in the case of a method call or other
		// side-effecting operation. The reason being that we may end up duplicating the
		// lhs. When the time comes, we can fix this by introducing an assignment
		// expression. This turns out to be a *very* convenient solution since all
		// target platforms support this. This would also help with the similar problem
		// of multiple returns.
		Tuple<Expr> returns = stmt.getReturns();
		E rval = null;
		Decl.FunctionOrMethod parent = stmt.getAncestor(Decl.FunctionOrMethod.class);
		Tuple<Type> targets = parent.getType().getReturns();
		if (targets.size() == 1) {
			rval = visitExpression(returns.get(0), targets.get(0));
		} else if (targets.size() > 1) {
			// FIXME: I think this is also broken in the case of mutliple return
			// expressions.
			Type.Record type = (Type.Record) getMultipleReturnType(targets);
			LowLevel.Type.Record llType = visitRecord(type);
			Identifier[] fields = new Identifier[targets.size()];
			ArrayList<E> results = new ArrayList<>();
			for (int i = 0; i != fields.length; ++i) {
				fields[i] = new Identifier("f" + i);
				results.add(visitExpression(returns.get(i), targets.get(i)));
			}
			rval = visitor.visitRecordInitialiser(llType, results);
		}
		return visitor.visitReturn(rval);
	}

	/**
	 * Translate a switch statement into a low level switch statement. The key
	 * challenge here is that switches in Whiley are much more flexible that
	 * low-level switches. Specifically, the former can switch on arbitrary values
	 * whilst the latter can only switch on integer values. To deal with this, we
	 * assume that the Whiley switch statement can be translated into a low-level
	 * switch. Then, if at some point we realise our assumption is false, we revert
	 * to a chaining approach.
	 *
	 * @param stmt
	 * @return
	 */
	public S visitSwitch(Stmt.Switch stmt) {
		E condition = visitExpression(stmt.getCondition(), Type.Int);
		List<wycc.util.Pair<Integer, List<S>>> branches = new ArrayList<>();
		Tuple<Stmt.Case> cases = stmt.getCases();
		for (int i = 0; i != cases.size(); ++i) {
			Stmt.Case cAse = cases.get(i);
			Tuple<Expr> conditions = cAse.getConditions();
			List<S> body = visitBlock(cAse.getBlock());
			if (cAse.isDefault()) {
				branches.add(new wycc.util.Pair<>(null, new ArrayList<>(body)));
			} else {
				for (int j = 0; j != conditions.size(); ++j) {
					Integer constant = extractIntegerConstant(conditions.get(j));
					if (constant == null) {
						// FIXME: handle this case
						return visitSwitchChain(stmt);
					}
					branches.add(new wycc.util.Pair<>(constant, new ArrayList<>(body)));
				}
			}
		}
		return visitor.visitSwitch(condition, branches);
	}

	public S visitSwitchChain(Stmt.Switch stmt) {
		Type lhsT = stmt.getCondition().getType();
		LowLevel.Type llLhsT = visitType(lhsT);
		E lhs = visitExpression(stmt.getCondition(), lhsT);
		List<wycc.util.Pair<E, List<S>>> branches = new ArrayList<>();
		Tuple<Stmt.Case> cases = stmt.getCases();
		for (int i = 0; i != cases.size(); ++i) {
			Stmt.Case cAse = cases.get(i);
			Tuple<Expr> conditions = cAse.getConditions();
			List<S> body = visitBlock(cAse.getBlock());
			for (int j = 0; j != conditions.size(); ++j) {
				Expr e = conditions.get(j);
				E rhs = visitExpression(e, e.getType());
				LowLevel.Type llRhsT = visitType(e.getType());
				E condition = visitor.visitEqual(llLhsT, llRhsT, lhs, rhs);
				branches.add(new wycc.util.Pair<>(condition, new ArrayList<>(body)));
			}
		}
		return visitor.visitIfElse(branches);
	}

	public S visitSkip(Stmt.Skip stmt) {
		return null;
	}

	/**
	 * Translation of While statements is relatively straightforward. The only
	 * interesting issue is how loop invariants are checked in debug mode.
	 *
	 * @param stmt
	 * @return
	 */
	public S visitWhile(Stmt.While stmt) {
		E condition = visitExpression(stmt.getCondition(), Type.Bool);
		Stmt.Block block = stmt.getBody();
		ArrayList<S> body = new ArrayList<>();
		for (int i = 0; i != block.size(); ++i) {
			body.addAll(visitStatement(block.get(i)));
		}
		return visitor.visitWhile(condition, body);
	}

	// ==========================================================================
	// Expressions
	// ==========================================================================

	/**
	 * Translate a (potentially) multiple expression whose values will be stored in
	 * several locations. In the case that there is only one target type, then this
	 * defaults to translating an expression in the usual fashion. However, in the
	 * case we're expecting more than one result, then it creates an appropriate
	 * wrapper (i.e. record) type to pass as the target type to the standard
	 * translation. For example, consider this:
	 *
	 * <pre>
	 * function swap(int x, int y) -> (int a, int b):
	 *    ...
	 *
	 * function other(...) -> (int a, int b)
	 *    ...
	 *    return swap(x,y)
	 * </pre>
	 *
	 * The <code>return</code> statement will call this method with two
	 * <code>int</code> target types. These are then wrapped into a wrapper and
	 * passed up the chain to the invocation.
	 *
	 * @param expr
	 * @param targets
	 * @return
	 */
	public E visitMultipleExpression(Expr expr, Tuple<Type> targets) {
		Type type;
		if (targets.size() == 1) {
			// Standard case, no multiple return required.
			type = targets.get(0);
		} else {
			// Complex case, multiple return is required.
			type = getMultipleReturnType(targets);
		}
		return visitExpression(expr, type);
	}

	/**
	 * Translate zero or more expressions into a corresponding number of
	 * productions. All translation is deferred to the single expression translator,
	 * including all issues related to coercions.
	 *
	 * @param expr
	 *            Expressions to be translated
	 * @param target
	 *            Target types for each expression. It's assumed this matches the
	 *            number of expressions.
	 * @return
	 */
	public List<E> visitExpressions(Tuple<Expr> exprs, Tuple<Type> targets) {
		// REQUIRES exprs.size() == targets.size();
		ArrayList<E> result = new ArrayList<>();
		for (int i = 0; i != exprs.size(); ++i) {
			result.add(visitExpression(exprs.get(i), targets.get(i)));
		}
		return result;
	}

	/**
	 * Translate zero or more expressions into a corresponding number of
	 * productions. All translation is deferred to the single expression translator,
	 * including all issues related to coercions.
	 *
	 * @param expr
	 *            Expressions to be translated
	 * @param target
	 *            Target types for each expression. It's assumed this matches the
	 *            number of expressions.
	 * @return
	 */
	public List<E> visitExpressions(Tuple<Expr> exprs, Type target) {
		// REQUIRES exprs.size() == targets.size();
		ArrayList<E> result = new ArrayList<>();
		for (int i = 0; i != exprs.size(); ++i) {
			result.add(visitExpression(exprs.get(i), target));
		}
		return result;
	}

	/**
	 * Translate a given expression whose value will be stored in a location of a
	 * given target type. Implicit datatype coercions will be inserted as necessary
	 * to ensure a value of the appropriate representation is returned. For example:
	 *
	 * <pre>
	 * int:16 y
	 * int:32 x = y
	 * </pre>
	 *
	 * This will introduce a coercion from <code>int:16</code> to
	 * <code>int:32</code>.
	 *
	 * Finally, since the target type is known to be an atom, tagging is not
	 * necessary.
	 *
	 * @param expr
	 *            The expression being translated.
	 * @param target
	 *            The target type for the result of this expression. If necessary,
	 *            coercions should be inserted to ensure this is the case.
	 * @return
	 */
	public E visitExpression(Expr expr, Type target) {
		switch (expr.getOpcode()) {
		case EXPR_arraygenerator:
			return visitArrayGenerator((Expr.ArrayGenerator) expr, target);
		case EXPR_arrayinitialiser:
			return visitArrayInitialiser((Expr.ArrayInitialiser) expr, target);
		case EXPR_arrayaccess:
		case EXPR_arrayborrow:
			return visitArrayAccess((Expr.ArrayAccess) expr, target);
		case EXPR_arraylength:
			return visitArrayLength((Expr.ArrayLength) expr, target);
		case EXPR_bitwisenot:
			return visitBitwiseComplement((Expr.BitwiseComplement) expr, target);
		case EXPR_bitwiseand:
		case EXPR_bitwiseor:
		case EXPR_bitwisexor:
			return visitBitwiseNaryOperator((Expr.NaryOperator) expr, target);
		case EXPR_bitwiseshl:
		case EXPR_bitwiseshr:
			return visitBitwiseShiftOperator((Expr.BinaryOperator) expr, target);
		case EXPR_cast:
			return visitCast((Expr.Cast) expr, target);
		case EXPR_constant:
			return visitConstantInitialiser((Expr.Constant) expr, target);
		case EXPR_dereference:
			return visitDereference((Expr.Dereference) expr, target);
		case EXPR_equal:
		case EXPR_notequal:
			return visitEquality((Expr.BinaryOperator) expr, target);
		case EXPR_integerlessthan:
		case EXPR_integerlessequal:
		case EXPR_integergreaterthan:
		case EXPR_integergreaterequal:
			return visitIntegerComparator((Expr.BinaryOperator) expr, target);
		case EXPR_integernegation:
			return visitIntegerNegation((Expr.IntegerNegation) expr, target);
		case EXPR_integeraddition:
		case EXPR_integersubtraction:
		case EXPR_integermultiplication:
		case EXPR_integerdivision:
		case EXPR_integerremainder:
			return visitIntegerOperator((Expr.BinaryOperator) expr, target);
		case EXPR_indirectinvoke:
			return visitIndirectInvoke((Expr.IndirectInvoke) expr, target);
		case EXPR_invoke:
			return visitInvoke((Expr.Invoke) expr, target);
		case EXPR_is:
			return visitIs((Expr.Is) expr);
		case DECL_lambda:
			return visitLambda((Decl.Lambda) expr, target);
		case EXPR_lambdaaccess:
			return visitLambdaAccess((Expr.LambdaAccess) expr, target);
		case EXPR_logicalnot:
			return visitLogicalNot((Expr.LogicalNot) expr);
		case EXPR_logiaclimplication:
		case EXPR_logicaliff:
			return visitLogicalBinaryOperator((Expr.BinaryOperator) expr);
		case EXPR_logicaland:
		case EXPR_logicalor:
			return visitLogicalNaryOperator((Expr.NaryOperator) expr);
		case EXPR_logicalexistential:
		case EXPR_logicaluniversal:
			return visitQuantifier((Expr.Quantifier) expr);
		case EXPR_recordaccess:
		case EXPR_recordborrow:
			return visitRecordAccess((Expr.RecordAccess) expr, target);
		case EXPR_recordinitialiser:
			return visitRecordInitialiser((Expr.RecordInitialiser) expr, target);
		case EXPR_staticvariable:
			return visitStaticVariableAccess((Expr.StaticVariableAccess) expr, target);
		case EXPR_variablecopy:
		case EXPR_variablemove:
			return visitVariableAccess((Expr.VariableAccess) expr, target);
		case EXPR_new:
		case EXPR_staticnew:
			return visitNew((Expr.New) expr, target);
		default:
			throw new IllegalArgumentException("invalid expression encountered (" + expr.getClass().getName() + ")");
		}
	}

	/**
	 * <p>
	 * Translation of array accesses is straightforward in the simple case, and
	 * non-trivial in the complex case. A key factor here is that we will not
	 * attempt to change the representation of the source array, since this would be
	 * potentially very expensive. The following illustrates:
	 * </p>
	 *
	 * <pre>
	 * int:16[] xs
	 * int:32 x = xs[0]
	 * </pre>
	 *
	 * <p>
	 * Some kind of coercion is necessary here and there are two ways we can do it.
	 * We could first coerce the entire <code>xs</code> array to be
	 * <code>int:32[]</code> (obviously a bad idea). The alternative is to cast the
	 * result after reading the item from <code>xs[0]</code>.
	 * </p>
	 *
	 * <p>
	 * The second main problem relates to the concept of a <i>readable array
	 * type</i>. For example, the type <code>int[]|bool[]</code> has a readable
	 * array type of <code>(int|bool)[]</code>. This means the following will type
	 * check:
	 * </p>
	 *
	 * <pre>
	 * function read(int[]|bool[] xs) -> (int|bool x):
	 *     return xs[0]
	 * </pre>
	 *
	 * <p>
	 * Whilst the benefits of allowing this may be unclear for arrays, it is
	 * important for records (and the mechanism is largely the same). Eitherway,
	 * performing an optimal translation of this depends somewhat on the underlying
	 * platform. In some cases we can read the element directly without problem; in
	 * otherwise, we need to include an "accessor" function which examines the
	 * relevant type tags.
	 * </p>
	 *
	 * @param expr
	 * @param _target
	 * @return
	 */
	public E visitArrayAccess(Expr.ArrayAccess expr, Type target) {
		Type sourceT = expr.getFirstOperand().getType();
		E source = visitExpression(expr.getFirstOperand(), sourceT);
		// FIXME: should be usize?
		E index = visitExpression(expr.getSecondOperand(), Type.Int);
		List<LowLevel.Type.Array> types = extractArrayTypes(visitType(sourceT));
		E result = visitor.visitArrayAccess(types, source, index);
		// Apply any coercions as necessary. This is especially important here as we
		// won't change the representation of the source array (for performance
		// reasons). Thus, coercions are likely to be required.
		return applyCoercion(target, expr.getType(), result);
	}

	/**
	 * An array generator is not an expression supported in most languages.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitArrayGenerator(Expr.ArrayGenerator expr, Type target) {
		Type.Array type = extractTargetType(target, (Type.Array) expr.getType());
		E value = visitExpression(expr.getFirstOperand(), type.getElement());
		E length = visitExpression(expr.getSecondOperand(), Type.Int);
		return visitor.visitArrayGenerator(visitArray(type), value, length);
	}

	/**
	 * Construct an appropriate array initialiser. In the simplest case, no
	 * coercions are necessary:
	 *
	 * <pre>
	 * bool[] x = [true,false,true]
	 * </pre>
	 *
	 * In a more complex case, element coercions maybe necessary:
	 *
	 * <pre>
	 * int:16[] x = [1,2,6] // elements => int:16
	 * </pre>
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitArrayInitialiser(Expr.ArrayInitialiser expr, Type _target) {
		// FIXME: should override ArrayInitialiser.getType() here?
		Type.Array target = extractTargetType(_target, (Type.Array) expr.getType());
		LowLevel.Type.Array llType = visitArray(target);
		// Translate the initialiser operands
		Tuple<Expr> operands = expr.getOperands();
		List<E> nOperands = new ArrayList<>();
		for (int i = 0; i != operands.size(); i++) {
			nOperands.add(visitExpression(operands.get(i), target.getElement()));
		}
		// Construct the initialiser itself
		E result = visitor.visitArrayInitialiser(llType, nOperands);
		// Apply any coercions as necessary
		return applyCoercion(_target, target, result);
	}

	public E visitArrayLength(Expr.ArrayLength expr, Type target) {
		// FIXME: The following is completely broken because we might have a readable
		// array type which doesn't make sense here.
		Type.Array sourceT = extractTargetArrayType(expr.getOperand().getType(), null);
		LowLevel.Type.Array llType = visitArray(sourceT);
		E source = visitExpression(expr.getOperand(), sourceT);
		E result = visitor.visitArrayLength(llType, source);
		return applyCoercion(target, expr.getType(), result);
	}

	public E visitBitwiseComplement(Expr.BitwiseComplement expr, Type target) {
		E operand = visitExpression(expr.getOperand(), Type.Byte);
		LowLevel.Type.Int llType = visitByte(Type.Byte);
		return visitor.visitBitwiseNot(llType, operand);
	}

	public E visitBitwiseNaryOperator(Expr.NaryOperator expr, Type target) {
		List<E> args = visitExpressions(expr.getOperands(), Type.Byte);
		LowLevel.Type.Int llType = visitByte(Type.Byte);
		E result = args.get(0);
		// Construct final operation
		for (int i = 1; i != args.size(); ++i) {
			switch (expr.getOpcode()) {
			case EXPR_bitwiseand:
				result = visitor.visitBitwiseAnd(llType, result, args.get(i));
				break;
			case EXPR_bitwiseor:
				result = visitor.visitBitwiseOr(llType, result, args.get(i));
				break;
			case EXPR_bitwisexor:
				result = visitor.visitBitwiseXor(llType, result, args.get(i));
				break;
			default:
				throw new IllegalArgumentException("invalid logical operator");
			}
		}
		return result;
	}

	public E visitBitwiseShiftOperator(Expr.BinaryOperator expr, Type target) {
		E lhs = visitExpression(expr.getFirstOperand(), Type.Byte);
		E rhs = visitExpression(expr.getSecondOperand(), Type.Int);
		LowLevel.Type.Int llType = visitByte(Type.Byte);
		switch (expr.getOpcode()) {
		case EXPR_bitwiseshl:
			return visitor.visitBitwiseShl(llType, lhs, rhs);
		case EXPR_bitwiseshr:
			return visitor.visitBitwiseShr(llType, lhs, rhs);
		default:
			throw new IllegalArgumentException("invalid bitwise shift operator");
		}
	}

	/**
	 * A cast forces an upstream coercion. For example, consider this scenario:
	 *
	 * <pre>
	 * int:16 x = ...
	 * int:32 y = (int:32) (x + 1)
	 * </pre>
	 *
	 * This will then force a coercion on <code>x</code> at the point it is read
	 * and, likewise, will result in <code>1</code> being automatically loaded as a
	 * 32bit value.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitCast(Expr.Cast expr, Type target) {
		E lhs = visitExpression(expr.getOperand(), expr.getType());
		return applyCoercion(target, expr.getType(), lhs);
	}

	/**
	 * Initialise a primitive value into a location of a specific type. For example:
	 *
	 * <pre>
	 * int:16 x = 0
	 * </pre>
	 *
	 * In this case, we're initialising an <code>int:16</code> location with the bit
	 * representation for zero.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitConstantInitialiser(Expr.Constant expr, Type target) {
		Value value = expr.getValue();
		E result;
		if (value instanceof Value.Null) {
			result = visitor.visitNullInitialiser();
		} else if (value instanceof Value.Bool) {
			Value.Bool b = (Value.Bool) value;
			result = visitor.visitLogicalInitialiser(b.get());
		} else if (value instanceof Value.Byte) {
			Value.Byte b = (Value.Byte) value;
			LowLevel.Type.Int type = visitor.visitTypeInt(8);
			result = visitor.visitIntegerInitialiser(type, BigInteger.valueOf(b.get() & 0xFF));
		} else if (value instanceof Value.UTF8) {
			Value.UTF8 b = (Value.UTF8) value;
			byte[] bs = b.get();
			ArrayList<E> values = new ArrayList<>();
			LowLevel.Type.Int type = visitor.visitTypeInt(8);
			for (int i = 0; i != bs.length; ++i) {
				values.add(visitor.visitIntegerInitialiser(type, BigInteger.valueOf(bs[i])));
			}
			result = visitor.visitArrayInitialiser(visitor.visitTypeArray(type), values);
		} else {
			Type.Int t = extractTargetIntegerType(target, expr.getType());
			Value.Int i = (Value.Int) value;
			// FIXME: t should encode the required width. For now, assuming it's always
			// unbounded (i.e. -1)
			LowLevel.Type.Int type = visitor.visitTypeInt(-1);
			result = visitor.visitIntegerInitialiser(type, i.get());
		}
		// Finally, apply any necessary coercions. For example, if this is going into a
		// union.
		return applyCoercion(target, expr.getType(), result);
	}

	public E visitDereference(Expr.Dereference expr, Type target) {
		throw new UnsupportedOperationException("implement me!");
	}

	/**
	 * Translate an equality expression. There are three main cases. In the first
	 * (simple) case, we are comparing items of primitive type (and the compile
	 * already ensures they are the same). In the second case, we are comparing
	 * compound items of the same type, and this may require some traversal. In the
	 * final (hardest) case, we are comparing one or more items of different type
	 * and this necessarily involves a union somewhere.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitEquality(Expr.BinaryOperator expr, Type target) {
		Expr lhs = expr.getFirstOperand();
		Expr rhs = expr.getSecondOperand();
		Type lhsT = lhs.getType();
		Type rhsT = rhs.getType();
		LowLevel.Type llLhsT = visitType(lhsT);
		LowLevel.Type llRhsT = visitType(rhsT);
		// Translate operands as need to do this regardless
		E left = visitExpression(lhs, lhsT);
		E right = visitExpression(rhs, rhsT);
		// Now decide what situation we're in.
		if (expr instanceof Expr.Equal) {
			return visitor.visitEqual(llLhsT, llRhsT, left, right);
		} else {
			return visitor.visitNotEqual(llLhsT, llRhsT, left, right);
		}
	}

	public E visitIs(Expr.Is expr) {
		Type lhsT = expr.getOperand().getType();
		Type rhsT = expr.getTestType();
		E operand = visitExpression(expr.getOperand(), lhsT);
		return constructRuntimeTypeTest(operand, lhsT, rhsT);
	}

	public E constructRuntimeTypeTest(E expr, Type actual, Type test) {
		if(actual.equals(test)) {
			return visitor.visitLogicalInitialiser(true);
		} else if (actual.getOpcode() == test.getOpcode()) {
			switch (actual.getOpcode()) {
			case TYPE_null:
			case TYPE_bool:
			case TYPE_byte:
			case TYPE_int:
				return visitor.visitLogicalInitialiser(true);
			case TYPE_union:
				return constructRuntimeTypeTest(expr, actual, (Type.Union) test);
			case TYPE_nominal:
				return constructRuntimeTypeTest(expr, actual, (Type.Nominal) test);
			default:
				throw new RuntimeException("need to work harder on type tests (" + actual + " is " + test + ")");
			}
		} else if (actual instanceof Type.Nominal) {
			return constructRuntimeTypeTest(expr, (Type.Nominal) actual, test);
		} else if (test instanceof Type.Nominal) {
			return constructRuntimeTypeTest(expr, actual, (Type.Nominal) test);
		} else if (actual instanceof Type.Union) {
			return constructRuntimeTypeTest(expr, (Type.Union) actual, test);
		} else if (test instanceof Type.Union) {
			return constructRuntimeTypeTest(expr, actual, (Type.Union) test);
		} else {
			throw new IllegalArgumentException("need to implement runtime tests better");
		}
	}

	public E constructRuntimeTypeTest(E expr, Type.Nominal actual, Type test) {
		try {
			WhileyFile.Decl.Type decl = typeSystem.resolveExactly(actual.getName(), WhileyFile.Decl.Type.class);
			return constructRuntimeTypeTest(expr, decl.getType(), test);
		} catch (ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	public E constructRuntimeTypeTest(E expr, Type actual, Type.Nominal test) {
		try {
			WhileyFile.Decl.Type decl = typeSystem.resolveExactly(test.getName(), WhileyFile.Decl.Type.class);
			E result = constructRuntimeTypeTest(expr, actual, decl.getType());
			if(decl.getInvariant().size() > 0) {
				// Type invariants are present so invoke invariant method to check them
				List<LowLevel.Type> parameters = new ArrayList<>();
				parameters.add(visitType(actual));
				LowLevel.Type.Method type = visitor.visitTypeMethod(parameters, visitBool(Type.Bool));
				String name = decl.getName().toString() + "$inv";
				List<E> arguments = new ArrayList<>();
				arguments.add(expr);
				result = visitor.visitLogicalAnd(result, visitor.visitDirectInvocation(type, name, arguments));
			}
			return result;
		} catch (ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	public E constructRuntimeTypeTest(E expr, Type.Union actual, Type test) {
		LowLevel.Type.Int tagT = visitInt(Type.Int);
		LowLevel.Type.Union llActualT = visitUnion(actual);
		int tag = determineTag(actual, test);
		Type refined = actual.get(tag);
		expr = visitor.visitUnionAccess(llActualT, expr);
		expr = visitor.visitIntegerEqual(tagT, expr, visitor.visitIntegerInitialiser(tagT, BigInteger.valueOf(tag)));
		// FIXME: type invariants
		if (!refined.equals(test)) {
			// FIXME: there maybe other situations where actual is equivalent to test, or
			// perhaps smaller than test?
			// WyllFile.Expr rest = constructRuntimeTypeTest(addCoercion(expr, actual,
			// refined), refined, test);
			// expr = new WyllFile.Expr.LogicalAnd(new Tuple<>(expr,rest));
			throw new IllegalArgumentException("need to work harder");
		}
		return expr;
	}

	/**
	 * Translate a runtime type test such as the following:
	 *
	 * <pre>
	 * type neg is (int p) where p < 0
	 * type pos is (int p) where p > 0
	 *
	 * function f(int x) -> bool:
	 *     return x is pos|neg
	 * </pre>
	 *
	 * This is expanded into roughly the following lowlevel code:
	 *
	 * <pre>
	 * bool neg$inv(int p) {
	 * 	return p < 0;
	 * }
	 *
	 * bool pos$inv(int p) {
	 * 	return p > 0;
	 * }
	 *
	 * bool f(int x) {
	 * 	return neg$inv(x) || pos$inv(x);
	 * }
	 * </pre>
	 *
	 * The key is that the different cases in the test union are translated into
	 * logical disjunctions.
	 *
	 * @param expr
	 * @param actual
	 * @param test
	 * @return
	 */
	public E constructRuntimeTypeTest(E expr, Type actual, Type.Union test) {
		E result = null;
		//
		for(int tag=0;tag!=test.size();++tag) {
			E clause = constructRuntimeTypeTest(expr,actual,test.get(tag));
			if(result == null) {
				result = clause;
			} else {
				result = visitor.visitLogicalOr(result, clause);
			}
		}
		//
		return result;
	}

	public E visitIntegerNegation(Expr.IntegerNegation expr, Type target) {
		E operand = visitExpression(expr.getOperand(), target);
		Type.Int type = extractTargetIntegerType(target, expr.getType());
		LowLevel.Type.Int llType = visitInt(type);
		return visitor.visitIntegerNegate(llType, operand);
	}

	/**
	 * An integer comparator needs to ensure the argument types have the same width,
	 * and must coerce them as necessary. For example:
	 *
	 * <pre>
	 * function f(int:8 x, int:16 y) -> (bool r):
	 *     if x > y:
	 *        return true
	 *     else:
	 *        return false
	 * </pre>
	 *
	 * In order for the comparison to be (in some sense) meaningful we want the
	 * operands to have the same width. Therefore, we coerce <code>x</code> to an
	 * <code>int:32</code>.
	 *
	 * @param expr
	 * @return
	 */
	public E visitIntegerComparator(Expr.BinaryOperator expr, Type target) {
		Type.Int leftT = extractTargetIntegerType(expr.getFirstOperand().getType(), Type.Int);
		Type.Int rightT = extractTargetIntegerType(expr.getSecondOperand().getType(), Type.Int);
		// Determine the "operating type" as this is the only safe type for which the
		// operation can succeed without overflow.
		Type.Int operatingT = max(leftT, rightT);
		// Translate the operands
		E lhs = visitExpression(expr.getFirstOperand(), operatingT);
		E rhs = visitExpression(expr.getSecondOperand(), operatingT);
		E result;
		LowLevel.Type.Int type = visitInt(operatingT);
		// Construct final operation
		switch (expr.getOpcode()) {
		case EXPR_equal:
			result = visitor.visitIntegerEqual(type, lhs, rhs);
			break;
		case EXPR_notequal:
			result = visitor.visitIntegerNotEqual(type, lhs, rhs);
			break;
		case EXPR_integerlessthan:
			result = visitor.visitIntegerLessThan(type, lhs, rhs);
			break;
		case EXPR_integerlessequal:
			result = visitor.visitIntegerLessThanOrEqual(type, lhs, rhs);
			break;
		case EXPR_integergreaterthan:
			result = visitor.visitIntegerGreaterThan(type, lhs, rhs);
			break;
		case EXPR_integergreaterequal:
			result = visitor.visitIntegerGreaterThanOrEqual(type, lhs, rhs);
			break;
		default:
			throw new IllegalArgumentException("invalid integer comparator");
		}
		// Finally, apply a coercion (if necessary) to ensure the result is represented
		// correctly for the target type. This might involve tagging as necessary.
		return applyCoercion(target, Type.Bool, result);
	}

	/**
	 * An integer operator needs to ensure the argument types have the same width
	 * and the result cannot overflow. For example:
	 *
	 * <pre>
	 * int:8 x = ...
	 * int:16 y = ...
	 * int:32 z = x + y
	 * </pre>
	 *
	 * The verified guarantees that the result will fit in an <code>int:32</code>.
	 * Therefore, the operation has to be performed with 32bits worth of precision,
	 * meaning both <code>x</code> and <code>y</code> must be coerced. In contract,
	 * consider this:
	 *
	 * <pre>
	 * int:32 x = ...
	 * int:16 y = ...
	 * int:8 z = x + y
	 * </pre>
	 *
	 * This may seem somewhat counter-intuitive. Again, the verifier will guarantee
	 * the result fits in an <code>int:8</code>. But it doesn't guarantee that
	 * <code>x</code> or <code>y</code> can without losing precision. Hence, again,
	 * the operation is performed with 32bits worth of precision. This means we must
	 * coerce <code>y</code> to an <code>int:32</code> and then coerce the result to
	 * an <code>int:8</code>.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitIntegerOperator(Expr.BinaryOperator expr, Type target) {
		Type.Int resT = extractTargetIntegerType(target, expr.getType());
		Type.Int leftT = extractTargetIntegerType(expr.getFirstOperand().getType(), Type.Int);
		Type.Int rightT = extractTargetIntegerType(expr.getSecondOperand().getType(), Type.Int);
		// Determine the "operating type" as this is the only safe type for which the
		// operation can succeed without overflow.
		Type.Int operatingT = max(leftT, rightT, resT);
		// Translate the operands
		E lhs = visitExpression(expr.getFirstOperand(), operatingT);
		E rhs = visitExpression(expr.getSecondOperand(), operatingT);
		E result;
		LowLevel.Type.Int type = visitInt(operatingT);
		// Construct final operation
		switch (expr.getOpcode()) {
		case EXPR_integeraddition:
			result = visitor.visitIntegerAdd(type, lhs, rhs);
			break;
		case EXPR_integersubtraction:
			result = visitor.visitIntegerSubtract(type, lhs, rhs);
			break;
		case EXPR_integermultiplication:
			result = visitor.visitIntegerMultiply(type, lhs, rhs);
			break;
		case EXPR_integerdivision:
			result = visitor.visitIntegerDivide(type, lhs, rhs);
			break;
		case EXPR_integerremainder:
			result = visitor.visitIntegerRemainder(type, lhs, rhs);
			break;
		default:
			throw new IllegalArgumentException("invalid integer operator");
		}
		// Finally, apply a coercion (if necessary) to ensure the result is represented
		// correctly for the target type. This might involve an integer coercion or
		// tagging as necessary.
		return applyCoercion(target, operatingT, result);
	}

	/**
	 * Translate an invocation for a function, method or property into a low level
	 * method invocation. This is relatively straightforward, with the only
	 * complication arising with multiple returns.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitInvoke(Expr.Invoke expr, Type target) {
		Type.Callable type = expr.getSignature();
		String name = getMangledName(expr.getName().toString(), type);
		LowLevel.Type.Method llType = (LowLevel.Type.Method) visitType(type);
		// Translate invocation arguments
		List<E> arguments = visitExpressions(expr.getOperands(), type.getParameters());
		// Construct the invocation
		E result = visitor.visitDirectInvocation(llType, name, arguments);
		// Finally, apply a coercion (if necessary) to ensure the result is represented
		// correctly for the target type. Care needs to be taken when handling multiple
		// returns.
		return applyCoercion(target, getMultipleReturnType(type.getReturns()), result);
	}

	public E visitIndirectInvoke(Expr.IndirectInvoke expr, Type target) {
		Expr source = expr.getSource();
		Type.Callable type = extractCallableType(source.getType());
		LowLevel.Type.Method llType = (LowLevel.Type.Method) visitType(type);
		// Translate indirect target
		E llSource = visitExpression(source, type);
		// Translate invocation arguments
		List<E> llArguments = visitExpressions(expr.getArguments(), type.getParameters());
		//
		return visitor.visitIndirectInvocation(llType, llSource, llArguments);
	}

	public E visitLambda(Decl.Lambda expr, Type target) {
		throw new UnsupportedOperationException("implement me!");
	}

	public E visitLambdaAccess(Expr.LambdaAccess expr, Type target) {
		String name = getMangledName(expr.getName().toString(), expr.getSignature());
		LowLevel.Type.Method llType = (LowLevel.Type.Method) visitType(expr.getSignature());
		return visitor.visitLambdaAccess(llType, name);
	}

	public E visitLogicalNot(Expr.LogicalNot expr) {
		E e = visitExpression(expr.getOperand(), Type.Bool);
		return visitor.visitLogicalNot(e);
	}

	public E visitLogicalBinaryOperator(Expr.BinaryOperator expr) {
		// Translate the operands
		E lhs = visitExpression(expr.getFirstOperand(), Type.Bool);
		E rhs = visitExpression(expr.getSecondOperand(), Type.Bool);
		// Construct final operation
		switch (expr.getOpcode()) {
		case EXPR_logiaclimplication:
			E tmp = visitor.visitLogicalNot(lhs);
			return visitor.visitLogicalOr(tmp, rhs);
		case EXPR_logicaliff:
			return visitor.visitLogicalEqual(lhs, rhs);
		default:
			throw new IllegalArgumentException("invalid logical operator");
		}
	}

	public E visitLogicalNaryOperator(Expr.NaryOperator expr) {
		// Translate the operands
		List<E> args = visitExpressions(expr.getOperands(), Type.Bool);
		E result = args.get(0);
		// Construct final operation
		for (int i = 1; i != args.size(); ++i) {
			switch (expr.getOpcode()) {
			case EXPR_logicaland:
				result = visitor.visitLogicalAnd(result, args.get(i));
				break;
			case EXPR_logicalor:
				result = visitor.visitLogicalOr(result, args.get(i));
				break;
			default:
				throw new IllegalArgumentException("invalid logical operator");
			}
		}
		return result;
	}

	/**
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitNew(Expr.New expr, Type target) {
		E operand = visitExpression(expr.getOperand(), Type.Bool);
		// FIXME: this is obviously broken
		LowLevel.Type.Reference type = (LowLevel.Type.Reference) expr.getType();
		return visitor.visitReferenceInitialiser(type, operand);
	}

	/**
	 * <p>
	 * Translation of record accesses is straightforward in the simple case, and
	 * non-trivial in the complex case. A key factor here is that we will not
	 * attempt to change the representation of the source record as, in most cases,
	 * this would be more expensive. The following illustrates:
	 * </p>
	 *
	 * <pre>
	 * {int:16 f} rec = ...
	 * int:32 x = rec.f
	 * </pre>
	 *
	 * <p>
	 * Some kind of coercion is necessary here and there are two ways we can do it.
	 * We could first coerce the record <code>reco</code> array to be
	 * <code>{int:32 f}</code> (most likely a bad idea). The alternative is to cast
	 * the result after reading the item from <code>rec.f</code>.
	 * </p>
	 *
	 * <p>
	 * The second main problem relates to the concept of a <i>readable record
	 * type</i>. For example, the type <code>{int f}|{bool f}</code> has a readable
	 * array type of <code>{int|bool f}</code>. This means the following will type
	 * check:
	 * </p>
	 *
	 * <pre>
	 * function read({int f}|{bool f} xs) -> (int|bool x):
	 *     return xs.f
	 * </pre>
	 *
	 * <p>
	 * This feature is actually quite important for enable simple interactions with
	 * record families, and shares similarity with the concept of a "common initial
	 * sequence" in C. However, performing an optimal translation of this depends
	 * somewhat on the underlying platform. In some cases we can read the element
	 * directly without problem; in otherwise, we need to include an "accessor"
	 * function which examines the relevant type tags, and then performs a low-level
	 * read based on this.
	 * </p>
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitRecordAccess(Expr.RecordAccess expr, Type target) {
		Type sourceT = expr.getOperand().getType();
		E source = visitExpression(expr.getOperand(), sourceT);
		// FIXME: this is clearly broken
		LowLevel.Type.Record type = (LowLevel.Type.Record) visitType(sourceT);
		E result = visitor.visitRecordAccess(type, source, expr.getField().toString());
		// Apply any coercions as necessary. This is especially important here as we
		// won't change the representation of the source array (for performance
		// reasons). Thus, coercions are likely to be required.
		return applyCoercion(target, expr.getType(), result);
	}

	/**
	 * Construct an appropriate record initialiser. In the simplest case, no
	 * coercions are necessary:
	 *
	 * <pre>
	 * {bool f} rec = {f:false}
	 * </pre>
	 *
	 * In a more complex case, element coercions maybe necessary:
	 *
	 * <pre>
	 * int:8 x
	 * {int:16 f} rec = {f:x} // x => int:16
	 * </pre>
	 *
	 * One complication is that the order of fields between the initialiser and the
	 * target type may not line up properly. For example:
	 *
	 * <pre>
	 * {int x, bool y} f = {y:false,x:0}
	 * </pre>
	 *
	 * In this case, it's important that reorder the initialiser appropriately.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitRecordInitialiser(Expr.RecordInitialiser expr, Type _target) {
		Type.Record target = extractTargetType(_target, expr.getType());
		LowLevel.Type.Record llType = visitRecord(target);
		// Translate the initialiser operands
		Tuple<Expr> operands = expr.getOperands();
		Tuple<Identifier> fields = expr.getFields();
		Tuple<Decl.Variable> targets = target.getFields();
		List<E> nOperands = new ArrayList<>();
		// FIXME: there is a problem here with the reordering process, in that it can
		// have observable effects in the case of expressions with side effects. To work
		// around this, we need to first evaluate them in the correct order into
		// temporaries. However, it's worth noting that not all platforms will actually
		// care about this.
		for (int i = 0; i != targets.size(); i++) {
			Decl.Variable field = targets.get(i);
			// Determine corresponding field initialiser. For various exciting reasons,
			// these may not line up exactly with the target type.
			int index = getFieldIndex(fields, field.getName());
			// Translate field initialiser into correct position for target type.
			nOperands.add(visitExpression(operands.get(index), field.getType()));
		}
		// Construct the initialiser
		E result = visitor.visitRecordInitialiser(llType, nOperands);
		// Apply any coercions as necessary
		return applyCoercion(_target, target, result);
	}

	/**
	 * Determine the index of a given field within a sequence of field names. It is
	 * assumed that field is present in the sequence.
	 *
	 * @param fields
	 * @param field
	 * @return
	 */
	private int getFieldIndex(Tuple<Identifier> fields, Identifier field) {
		for (int i = 0; i != fields.size(); ++i) {
			if (fields.get(i).equals(field)) {
				return i;
			}
		}
		throw new IllegalArgumentException("invalid field index");
	}

	/**
	 * A variable access operation is relatively straightforward, though care must
	 * be taken to ensure coercions are applied as necessary. This is necessary is
	 * where we are simply assigning the variable to a union, such as follows:
	 *
	 * <pre>
	 * int x = ...
	 * int|null y = x
	 * </pre>
	 *
	 * Here, a tagging operation is required to convert <code>x</code> for
	 * <code>int</code> to <code>int|null</code>.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitStaticVariableAccess(Expr.StaticVariableAccess expr, Type target) {
		try {
			WhileyFile.Decl.StaticVariable decl = typeSystem.resolveExactly(expr.getName(),
					WhileyFile.Decl.StaticVariable.class);
			LowLevel.Type llType = visitType(decl.getType());
			E result = visitor.visitStaticVariableAccess(llType, decl.getName().toString());
			// Apply any coercions as necessary to ensure return value is in the correct
			// form.
			return applyCoercion(target, decl.getType(), result);
		} catch (ResolutionError e) {
			// Should be deadcode
			throw new RuntimeException(e);
		}
	}

	/**
	 * A variable access operation is relatively straightforward, though care must
	 * be taken to ensure coercions are applied as necessary. There are two
	 * scenarios which arise. The first is where are simply assigning the variable
	 * to a union, such as follows:
	 *
	 * <pre>
	 * int x = ...
	 * int|null y = x
	 * </pre>
	 *
	 * Here, a tagging operationg is required to convert <code>x</code> for
	 * <code>int</code> to <code>int|null</code>. The second scenario arises from
	 * flow typing:
	 *
	 * <pre>
	 * function f(int|null x) -> (int r):
	 *    if x is int:
	 *       return x
	 *    ...
	 * </pre>
	 *
	 * Here, <code>x</code> needs to be coerced down from <code>int|null</code> to
	 * <code>int</code>. The flow type checker ensures this is a valid thing to do
	 * in this situation.
	 *
	 * @param expr
	 * @param target
	 * @return
	 */
	public E visitVariableAccess(Expr.VariableAccess expr, Type target) {
		Decl.Variable decl = expr.getVariableDeclaration();
		LowLevel.Type llType = visitType(decl.getType());
		E result = visitor.visitVariableAccess(llType, decl.getName().toString());
		// Check whether a clone operation is required or not
		if (!expr.isMove()) {
			// Yes, variable must be cloned
			result = constuctExpressionClone(llType, result);
		}
		// Apply any coercions as necessary to ensure return value is in the correct
		// form.
		return applyCoercion(target, decl.getType(), result);
	}

	/**
	 * Construct a clone of a given expression. For the moment, this just defers to
	 * the low level visitor. However, this is a temporary solution as real
	 * low-level targets, such as C, will require the method be provided.
	 *
	 * @param type
	 *            The type of the expression being clone
	 * @param expr
	 *            The expression being cloned.
	 * @return
	 */
	public E constuctExpressionClone(LowLevel.Type type, E expr) {
		// FIXME: this is not satisfactory.
		return visitor.visitClone(type, expr);
	}

	/**
	 * Quantifier expressions are interesting because they generally have no direct
	 * counterpart in the target language. Instead, they are implemented as for
	 * loops over the array in question. To support the embedding of a statement
	 * block as an expression, we employ internal methods. For example:
	 *
	 * <pre>
	 * assert some { k in 0..|xs| | xs[k] == 0 }
	 * </pre>
	 *
	 * This Whiley statement is translated into the following low-level statement:
	 *
	 * <pre>
	 * assert expr$1(xs);
	 * </pre>
	 *
	 * Where the internal method <code>expr$1</code> is defined as follows:
	 *
	 * <pre>
	 * method bool expr$1(int[] xs) {
	 *   for(int k=0;k!=|xs|;k=k+1) {
	 *      if(xs[k] == 0) {
	 *        return true;
	 *      }
	 *   }
	 *   return false;
	 * }
	 * </pre>
	 *
	 * This provides a relatively straightforward implementation of quantifiers. In
	 * principle, this could be optimised by inlining the method where appropriate.
	 *
	 * @param expr
	 * @return
	 */
	public E visitQuantifier(Expr.Quantifier expr) {
		String name = "expr$" + expr.getIndex();
		Set<WhileyFile.Decl.Variable> uses = determineUsedVariables(expr);
		List<wycc.util.Pair<LowLevel.Type, String>> parameters = constructQuantifierParameters(uses);
		E condition = visitExpression(expr.getOperand(), Type.Bool);
		// Create the method body, which contains the sequence of nested quantifier for
		// loops and the final return statement.
		List<S> body = constructQuantifierBody(expr, 0, condition);
		// Determine whether getting through the loops should return true or false
		E retval = visitor.visitLogicalInitialiser(expr instanceof Expr.UniversalQuantifier);
		body.add(visitor.visitReturn(retval));
		// Create the method body from the loops and the additional return statement
		auxillaries.add(visitor.visitMethod(name, parameters, visitor.visitTypeBool(), body));
		// Declare the method somehow?
		// Create an invocation to the block
		return visitor.visitDirectInvocation(constructQuantifierType(parameters), name,
				constructQuantifierArguments(uses));
	}

	public List<wycc.util.Pair<LowLevel.Type, String>> constructQuantifierParameters(
			Set<WhileyFile.Decl.Variable> uses) {
		ArrayList<wycc.util.Pair<LowLevel.Type, String>> parameters = new ArrayList<>();
		for (WhileyFile.Decl.Variable use : uses) {
			LowLevel.Type type = visitType(use.getType());
			parameters.add(new wycc.util.Pair<>(type, use.getName().toString()));
		}
		return parameters;
	}

	public List<S> constructQuantifierBody(Expr.Quantifier expr, int index, E condition) {
		Tuple<Decl.Variable> parameters = expr.getParameters();
		ArrayList<S> stmts = new ArrayList<>();
		if (index == parameters.size()) {
			// This indicates we are now within the innermost loop body. Therefore, we
			// create the necessary test for the quantifier condition.
			E retval = visitor.visitLogicalInitialiser(expr instanceof Expr.ExistentialQuantifier);
			S retstmt = visitor.visitReturn(retval);
			ArrayList<wycc.util.Pair<E, List<S>>> branches = new ArrayList<>();
			ArrayList<S> trueBlock = new ArrayList<>();
			trueBlock.add(retstmt);
			branches.add(new wycc.util.Pair<>(condition, trueBlock));
			stmts.add(visitor.visitIfElse(branches));
		} else {
			// This is the recursive case. For each parameter we create a nested foreach
			// loop which iterates over the given range
			Decl.Variable parameter = parameters.get(index);
			Expr.ArrayRange range = (Expr.ArrayRange) parameter.getInitialiser();
			// FIXME: should be usize
			E start = visitExpression(range.getFirstOperand(), Type.Int);
			E end = visitExpression(range.getSecondOperand(), Type.Int);
			// Construct index variable
			LowLevel.Type.Int varType = visitor.visitTypeInt(-1);
			S var = visitor.visitVariableDeclaration(varType, parameter.getName().get(), start);
			E varAccess = visitor.visitVariableAccess(varType, parameter.getName().get());
			E loopCondition = visitor.visitIntegerLessThan(varType, varAccess, end);
			E one = visitor.visitIntegerInitialiser(varType, BigInteger.ONE);
			S increment = visitor.visitAssign(varAccess, visitor.visitIntegerAdd(varType, varAccess, one));
			// Recursively create nested loops for remaining parameters
			List<S> body = constructQuantifierBody(expr, index + 1, condition);
			// Return the loop for this parameter
			stmts.add(visitor.visitFor(var, loopCondition, increment, body));
		}
		return stmts;
	}

	public List<E> constructQuantifierArguments(Set<WhileyFile.Decl.Variable> uses) {
		ArrayList<E> arguments = new ArrayList<>();
		for (WhileyFile.Decl.Variable use : uses) {
			LowLevel.Type type = visitType(use.getType());
			arguments.add(visitor.visitVariableAccess(type, use.getName().get()));
		}
		return arguments;
	}

	public LowLevel.Type.Method constructQuantifierType(List<wycc.util.Pair<LowLevel.Type, String>> parameters) {
		List<LowLevel.Type> result = new ArrayList<>();
		for (int i = 0; i != parameters.size(); ++i) {
			result.add(parameters.get(i).first());
		}
		return visitor.visitTypeMethod(result, visitor.visitTypeBool());
	}

	/**
	 * Apply a coercion from a given actual type to a required target type. This can
	 * involve physical data coercions as necessary, as well as tagging and
	 * untagging operations. For example:
	 *
	 * <pre>
	 * int|null x = 1
	 * </pre>
	 *
	 * Here, the target type of the right-hand side would be <code>int|null</code>
	 * and the actual type would be <code>int</code>. Hence, we need to perform a
	 * tagging operation. As another example:
	 *
	 * <pre>
	 * int:16 x = 1
	 * int:32 y = x
	 * </pre>
	 *
	 * For the second assignment, the target type of the right-hand side is
	 * <code>int:32</code> and its actual type is <code>int:16</code>. Hence, we
	 * need to apply an integer coercion (e.g. sign extension). As a final example
	 * to illustrate coercions of compound structures:
	 *
	 * <pre>
	 * int:16[] xs = [1,2,3]
	 * int:32[] ys = xs
	 * </pre>
	 *
	 * For the second assignment, we have to coerce the entire <code>xs</code> array
	 * into the appropriate format. This ammounts to a complete clone of the array,
	 * although this depends exactly on the target architecture. For example, on
	 * some architectures, <code>int:16</code> and <code>int:32</code> may have the
	 * same underlying representation and, hence, no coercion would be necessary.
	 *
	 * @param target
	 *            The required target type for the given expression. This is the
	 *            type that the value returned by the expression must meet.
	 * @param actual
	 *            The actual type returned by the given expression. This is the type
	 *            which we have, though it may not match the required target type.
	 *            If they don't match, then some kind of coercion is necessary.
	 * @param expr
	 * @return
	 */
	public E applyCoercion(Type target, Type actual, E expr) {
		if (target.equals(actual)) {
			// no coercion required in this case
			return expr;
		} else if (target instanceof Type.Int && actual instanceof Type.Int) {
			return applyIntCoercion((Type.Int) target, (Type.Int) actual, expr);
		} else if (target instanceof Type.Array && actual instanceof Type.Array) {
			return applyArrayCoercion((Type.Array) target, (Type.Array) actual, expr);
		} else if (target instanceof Type.Record && actual instanceof Type.Record) {
			return applyRecordCoercion((Type.Record) target, (Type.Record) actual, expr);
		} else if (target instanceof Type.Reference && actual instanceof Type.Reference) {
			return applyReferenceCoercion((Type.Reference) target, (Type.Reference) actual, expr);
		} else if (target instanceof Type.Nominal) {
			return applyNominalCoercion((Type.Nominal) target, actual, expr);
		} else if (actual instanceof Type.Nominal) {
			return applyNominalCoercion(target, (Type.Nominal) actual, expr);
		} else if (target instanceof Type.Union && actual instanceof Type.Union) {
			return applyUnionCoercion((Type.Union) target, (Type.Union) actual, expr);
		} else if (target instanceof Type.Union) {
			return applyUnionCoercion((Type.Union) target, actual, expr);
		} else if (actual instanceof Type.Union) {
			return applyUnionCoercion(target, (Type.Union) actual, expr);
		} else {
			throw new IllegalArgumentException("unknown coercion: " + actual + " => " + target);
		}
	}

	public E applyIntCoercion(Type.Int _target, Type.Int _actual, E expr) {
		LowLevel.Type.Int target = visitInt(_target);
		LowLevel.Type.Int actual = visitInt(_actual);
		return visitor.visitIntegerCoercion(target, actual, expr);
	}

	public E applyArrayCoercion(Type.Array target, Type.Array actual, E expr) {
		throw new RuntimeException("implement me!");
	}

	public E applyRecordCoercion(Type.Record target, Type.Record actual, E expr) {
		throw new RuntimeException("implement me!");
	}

	public E applyReferenceCoercion(Type.Reference target, Type.Reference actual, E expr) {
		throw new RuntimeException("implement me!");
	}

	public E applyNominalCoercion(Type.Nominal target, Type actual, E expr) {
		try {
			WhileyFile.Decl.Type decl = typeSystem.resolveExactly(target.getName(), WhileyFile.Decl.Type.class);
			return applyCoercion(decl.getType(),actual,expr);
		} catch (ResolutionError e) {
			throw new IllegalArgumentException("invalid nominal type (" + target + ")");
		}
	}

	public E applyNominalCoercion(Type target, Type.Nominal actual, E expr) {
		try {
			WhileyFile.Decl.Type decl = typeSystem.resolveExactly(actual.getName(), WhileyFile.Decl.Type.class);
			return applyCoercion(target,decl.getType(),expr);
		} catch (ResolutionError e) {
			throw new IllegalArgumentException("invalid nominal type (" + target + ")");
		}
	}

	private static int coercionIndex = 0;

	public E applyUnionCoercion(Type.Union target, Type.Union actual, E expr) {
		// Construct coercion method
		String name = "coercion$" + coercionIndex++;
		auxillaries.add(constructUnionUnionCoercion(name,target,actual));
		// Construct invocation to coercion method
		List<E> arguments = new ArrayList<>();
		arguments.add(expr);
		List<LowLevel.Type> parameterTypes = new ArrayList<>();
		parameterTypes.add(visitType(actual));
		LowLevel.Type.Method type = visitor.visitTypeMethod(parameterTypes, visitType(target));
		return visitor.visitDirectInvocation(type, name, arguments);
	}

	public D constructUnionUnionCoercion(String name, Type.Union target, Type.Union actual) {
		LowLevel.Type paramType = visitType(actual);
		LowLevel.Type retType = visitType(target);
		E parameter = visitor.visitVariableAccess(paramType, "val");
		ArrayList<S> stmts = new ArrayList<>();
		ArrayList<wycc.util.Pair<Integer, List<S>>> branches = new ArrayList();
		for(int i=0;i!=actual.size();++i) {
			Integer c = (i+1) == actual.size() ? null : i;
			List<S> branch = new ArrayList<>();
			E coercion = applyCoercion(target,actual.get(i),parameter);
			branch.add(visitor.visitReturn(coercion));
			branches.add(new wycc.util.Pair<>(c,branch));
		}
		stmts.add(visitor.visitSwitch(parameter,branches));
		List<wycc.util.Pair<LowLevel.Type, String>> parameters = new ArrayList<>();
		parameters.add(new wycc.util.Pair<>(paramType,"val"));
		return visitor.visitMethod(name, parameters, retType, stmts);
	}

	public E applyUnionCoercion(Type.Union target, Type actual, E expr) {
		int tag = determineTag(target,actual);
		Type element = target.get(tag);
		expr = applyCoercion(element,actual,expr);
		LowLevel.Type.Union llTarget = visitUnion(target);
		return visitor.visitUnionEnter(llTarget,tag,expr);
	}

	public E applyUnionCoercion(Type target, Type.Union actual, E expr) {
		int tag = determineTag(target,actual);
		Type element = actual.get(tag);
		expr = applyCoercion(target,element,expr);
		LowLevel.Type.Union llActual = visitUnion(actual);
		return visitor.visitUnionLeave(llActual, tag, expr);
	}
	// ==========================================================================
	// Type
	// ==========================================================================
	public List<LowLevel.Type> visitTypes(Tuple<Type> types) {
		ArrayList<LowLevel.Type> result = new ArrayList<>();
		for (int i = 0; i != types.size(); ++i) {
			result.add(visitType(types.get(i)));
		}
		return result;
	}

	public LowLevel.Type visitType(Type type) {
		switch (type.getOpcode()) {
		case TYPE_array:
			return visitArray((Type.Array) type);
		case TYPE_bool:
			return visitBool((Type.Bool) type);
		case TYPE_byte:
			return visitByte((Type.Byte) type);
		case TYPE_int:
			return visitInt((Type.Int) type);
		case TYPE_nominal:
			return visitNominal((Type.Nominal) type);
		case TYPE_null:
			return visitNull((Type.Null) type);
		case TYPE_record:
			return visitRecord((Type.Record) type);
		case TYPE_staticreference:
		case TYPE_reference:
			return visitReference((Type.Reference) type);
		case TYPE_function:
		case TYPE_method:
		case TYPE_property:
			return visitCallable((Type.Callable) type);
		case TYPE_union:
			return visitUnion((Type.Union) type);
		case TYPE_intersection:
			return visitIntersection((Type.Intersection) type);
		case TYPE_void:
			return visitVoid((Type.Void) type);
		default:
			throw new IllegalArgumentException("unknown type encountered (" + type.getClass().getName() + ")");
		}
	}

	public LowLevel.Type.Array visitArray(Type.Array type) {
		LowLevel.Type element = visitType(type.getElement());
		return visitor.visitTypeArray(element);
	}

	public LowLevel.Type.Void visitVoid(Type.Void type) {
		return visitor.visitTypeVoid();
	}

	public LowLevel.Type.Bool visitBool(Type.Bool type) {
		return visitor.visitTypeBool();
	}

	public LowLevel.Type.Int visitByte(Type.Byte type) {
		return visitor.visitTypeInt(8);
	}

	public LowLevel.Type.Int visitInt(Type.Int type) {
		// FIXME: currently all integer types are unbound.
		return visitor.visitTypeInt(-1);
	}

	public LowLevel.Type.Null visitNull(Type.Null type) {
		return visitor.visitTypeNull();
	}

	public LowLevel.Type.Record visitRecord(Type.Record type) {
		Tuple<Decl.Variable> fields = type.getFields();
		ArrayList<wycc.util.Pair<LowLevel.Type, String>> nFields = new ArrayList<>();
		for (int i = 0; i != fields.size(); ++i) {
			Decl.Variable field = fields.get(i);
			LowLevel.Type fieldType = visitType(field.getType());
			nFields.add(new wycc.util.Pair<>(fieldType, field.getName().toString()));
		}
		// FIXME: what to do about open records?
		return visitor.visitTypeRecord(nFields);
	}

	public LowLevel.Type.Reference visitReference(Type.Reference type) {
		LowLevel.Type element = visitType(type.getElement());
		return visitor.visitTypeReference(element);
	}

	public LowLevel.Type.Method visitCallable(Type.Callable type) {
		List<LowLevel.Type> parameters = visitTypes(type.getParameters());
		LowLevel.Type returns = visitType(getMultipleReturnType(type.getReturns()));
		return visitor.visitTypeMethod(parameters, returns);
	}

	public LowLevel.Type.Union visitUnion(Type.Union type) {
		ArrayList<LowLevel.Type> elements = new ArrayList<>();
		for (int i = 0; i != type.size(); ++i) {
			elements.add(visitType(type.get(i)));
		}
		return visitor.visitTypeUnion(elements);
	}

	public LowLevel.Type visitIntersection(Type.Intersection type) {
		// FIXME: implement this!
		throw new RuntimeException("implement visitIntersection");
	}

	public LowLevel.Type visitNominal(Type.Nominal type) {
		try {
			WhileyFile.Decl.Type decl = typeSystem.resolveExactly(type.getName(), WhileyFile.Decl.Type.class);
			if (decl.isRecursive()) {
				// FIXME: is this always the correct translation?
				return visitor.visitTypeRecursive(type.getName().toString());
			} else {
				return visitType(decl.getType());
			}
		} catch (NameResolver.ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	// ==========================================================================
	// Helpers
	// ==========================================================================

	public int determineTag(Type.Union parent, Type child) {
		for (int i = 0; i != parent.size(); ++i) {
			if (isSubtype(parent.get(i), child)) {
				return i;
			}
		}
		throw new IllegalArgumentException("cannot determine appropriate tag (" + parent + "<-" + child + ")");
	}

	public int determineTag(Type parent, Type.Union child) {
		for (int i = 0; i != child.size(); ++i) {
			if (isSubtype(parent, child.get(i))) {
				return i;
			}
		}
		throw new IllegalArgumentException("cannot determine appropriate tag (" + parent + "<-" + child + ")");
	}

	public boolean isSubtype(Type parent, Type child) {
		try {
			// FIXME: need to handle lifetimes properly
			return typeSystem.isRawSubtype(parent, child, null);
		} catch (ResolutionError e) {
			throw new RuntimeException("internal failure");
		}
	}

	/**
	 * Extract an integer constant from am integer constexpr or, if not, return
	 * null.
	 *
	 * @param e
	 * @return
	 */
	public Integer extractIntegerConstant(Expr e) {
		try {
			if (e instanceof Expr.Constant) {
				Expr.Constant c = (Expr.Constant) e;
				WhileyFile.Value v = c.getValue();
				if (v instanceof WhileyFile.Value.Int) {
					BigInteger bi = ((WhileyFile.Value.Int) v).get();
					return bi.intValueExact();
				}
			} else if (e instanceof Expr.IntegerNegation) {
				Expr.IntegerNegation ineg = (Expr.IntegerNegation) e;
				Integer r = extractIntegerConstant(ineg.getOperand());
				return r != null ? -r : null;
			}
		} catch (ArithmeticException exp) {
			// This can be thrown from the intValueExact method which indicates we cannot
			// extract an integer from this expression, hence we just return null.
			System.out.println("STAGE 4");
		}

		// No dice.
		return null;
	}

	/**
	 * Determine the set of used variables in a given expression. A used variable is
	 * simply one that is accessed from within the expression. Care needs to be
	 * taken for expressions which declare parameters in order to avoid capturing
	 * these.
	 *
	 * @param expr
	 * @return
	 */
	public Set<WhileyFile.Decl.Variable> determineUsedVariables(WhileyFile.Expr expr) {
		final HashSet<WhileyFile.Decl.Variable> used = new HashSet<>();
		// Create a translateor to extract all uses from the given expression.
		final AbstractVisitor translateor = new AbstractVisitor() {
			@Override
			public void visitVariableAccess(WhileyFile.Expr.VariableAccess expr) {
				used.add(expr.getVariableDeclaration());
			}

			@Override
			public void visitUniversalQuantifier(WhileyFile.Expr.UniversalQuantifier expr) {
				visitVariables(expr.getParameters());
				visitExpression(expr.getOperand());
				removeAllDeclared(expr.getParameters());
			}

			@Override
			public void visitExistentialQuantifier(WhileyFile.Expr.ExistentialQuantifier expr) {
				visitVariables(expr.getParameters());
				visitExpression(expr.getOperand());
				removeAllDeclared(expr.getParameters());
			}

			@Override
			public void visitType(WhileyFile.Type type) {
				// No need to visit types
			}

			private void removeAllDeclared(Tuple<Decl.Variable> parameters) {
				for (int i = 0; i != parameters.size(); ++i) {
					used.remove(parameters.get(i));
				}
			}
		};
		//
		translateor.visitExpression(expr);
		return used;
	}

	/**
	 * Determined the mangled name for a given function, method or property
	 * declaration. Mangling is required to handle overloading. Essentially, it
	 * encodes parameter type information into the name of the callable declaration
	 * to ensure overloaded declarations are distinct. For example, consider this:
	 *
	 * <pre>
	 * function id(int x) -> (int r):
	 *    return x
	 *
	 * function id(bool x) -> (bool r):
	 *    return x
	 * </pre>
	 *
	 * On a native platform we need to distinguish these two functions. For example,
	 * we might generate something like this (for JavaScript):
	 *
	 * <pre>
	 * function id_I(int x) -> (int r):
	 *    return x
	 *
	 * function id_B(bool x) -> (bool r):
	 *    return x
	 * </pre>
	 *
	 * Thus, we see that <code>_I</code> denotes a function which accepts an
	 * <code>int</code> parameter and <code>_B</code> one which accepts a boolean
	 * parameter. This uses the provided type mangler to construct the mangle.
	 * Observe that some declarations (e.g. native or export) are not mangled.
	 *
	 * @param decl
	 * @return
	 */
	public String getMangledName(Decl.Callable decl) {
		Tuple<Modifier> modifiers = decl.getModifiers();
		// First, check whether a mangle is actually required.
		if (modifiers.match(Modifier.Export.class) == null && modifiers.match(Modifier.Native.class) == null) {
			// Yes, mangle is required. Therefore, use mangler to generate it.
			return getMangledName(decl.getName().toString(), decl.getType());
		} else {
			// No mangle is required, therefore return name untouched.
			return decl.getName().toString();
		}
	}

	public String getMangledName(String name, Type.Callable type) {
		Tuple<Identifier> lifetimes;
		if (type instanceof Type.Method) {
			lifetimes = ((Type.Method) type).getLifetimeParameters();
		} else {
			lifetimes = new Tuple<>();
		}
		String mangle = mangler.getMangle(type.getParameters(), lifetimes);
		return name + mangle;
	}

	public String createTemporaryVariable(int i) {
		// FIXME: need to do better here!!!
		return "tmp$" + i;
	}

	/**
	 * Determine the appropriate return type for a given callable declaration. The
	 * key challenge here is that we assume target architectures do not support
	 * multiple return values (though, in principle, some might such as LLVM). In
	 * the case that multiple returns are employed, then a record is used to wrap
	 * them into a single return value. The following illustrates both scenarios:
	 *
	 * <pre>
	 * function id(int x) -> (int r):
	 *    return x
	 *
	 * function swap(int x, int y) -> (int a, int b):
	 *    return y,x
	 * </pre>
	 *
	 * These are effectively translated into the following equivalent forms:
	 *
	 * <pre>
	 * function id(int x) -> (int r):
	 *    return x
	 *
	 * function swap(int x, int y) -> {int a, int b}:
	 *    return {a:y, b:x}
	 * </pre>
	 *
	 * We see here that, when only one return is present, then this is used as is.
	 * However, when more than one is required, then they are just wrapped into a
	 * record.
	 *
	 * @param returns
	 * @return
	 */
	public Type getMultipleReturnType(Tuple<Type> returns) {
		if (returns.size() == 0) {
			// No single return value
			return Type.Void;
		} else if (returns.size() == 1) {
			// One return value, so use as is.
			return returns.get(0);
		} else {
			Decl.Variable[] fields = new Decl.Variable[returns.size()];
			for (int i = 0; i != fields.length; ++i) {
				Type type = returns.get(i);
				Identifier name = new Identifier("f" + i);
				fields[i] = new Decl.Variable(new Tuple<>(), name, type);
			}
			return new Type.Record(false, new Tuple<>(fields));
		}
	}

	public Type.Callable extractCallableType(Type target) {
		try {
			if (target instanceof Type.Callable) {
				return (Type.Callable) target;
			} else if (target instanceof Type.Nominal) {
				Type.Nominal type = (Type.Nominal) target;
				WhileyFile.Decl.Type decl = typeSystem.resolveExactly(type.getName(), WhileyFile.Decl.Type.class);
				return extractCallableType(decl.getType());
			} else {
				throw new UnsupportedOperationException("implement callable extraction (" + target + ")");
			}
		} catch (ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Given a target type for the result of an expression and the actual type of an
	 * expression, extract the target integer type. For example, consider this
	 * simple situation:
	 *
	 * <pre>
	 * null|int:16 x = 1
	 * </pre>
	 *
	 * The right-hand side is the expression of interest. The target type for this
	 * is <code>null|int:16</code>, whilst its actual type is just <code>int</code>.
	 * Thus, the target integer type is <code>int:16</code>.
	 *
	 * @param target
	 *            The overall target type for the expression in question
	 * @param actual
	 *            The stated result type for the expression in question. This is
	 *            necessary, for example, to determine which component of a union is
	 *            the actual target.
	 * @return
	 */
	public Type.Int extractTargetIntegerType(Type target, Type actual) {
		try {
			if (target instanceof Type.Int) {
				return (Type.Int) target;
			} else if (target instanceof Type.Union) {
				Type.Union type = (Type.Union) target;
				for (int i = 0; i != type.size(); ++i) {
					Type element = type.get(i);
					if (typeSystem.isRawCoerciveSubtype(element, actual, null)) {
						return extractTargetIntegerType(element, actual);
					}
				}
				throw new RuntimeException("deadcode reached");
			} else if (target instanceof Type.Nominal) {
				Type.Nominal type = (Type.Nominal) target;
				WhileyFile.Decl.Type decl = typeSystem.resolveExactly(type.getName(), WhileyFile.Decl.Type.class);
				return extractTargetIntegerType(decl.getType(), actual);
			} else {
				throw new UnsupportedOperationException(
						"implement target integer extraction (" + target + "<=" + actual + ")");
			}
		} catch (ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Given a target type for the result of an expression and the actual type of an
	 * expression, extract the target array type. For example, consider this simple
	 * situation:
	 *
	 * <pre>
	 * int:16[]|null x = [1]
	 * </pre>
	 *
	 * The right-hand side is the expression of interest. The target type for this
	 * is <code>int:16[]|null</code>, whilst its actual type is just
	 * <code>int[]</code>. Thus, the target array type is <code>int:16[]</code>.
	 *
	 * @param target
	 *            The overall target type for the expression in question
	 * @param actual
	 *            The stated result type for the expression in question. This is
	 *            necessary, for example, to determine which component of a union is
	 *            the actual target.
	 * @return
	 */
	public Type.Array extractTargetArrayType(Type target, Type.Array actual) {
		try {
			if (target instanceof Type.Array) {
				return (Type.Array) target;
			} else if (target instanceof Type.Union) {
				Type.Union type = (Type.Union) target;
				for (int i = 0; i != type.size(); ++i) {
					Type element = type.get(i);
					if (typeSystem.isRawCoerciveSubtype(element, actual, null)) {
						return extractTargetArrayType(element, actual);
					}
				}
				throw new RuntimeException("deadcode reached");
			} else if (target instanceof Type.Nominal) {
				Type.Nominal type = (Type.Nominal) target;
				WhileyFile.Decl.Type decl = typeSystem.resolveExactly(type.getName(), WhileyFile.Decl.Type.class);
				return extractTargetArrayType(decl.getType(), actual);
			} else {
				throw new UnsupportedOperationException("implement target array extraction (" + target + ")");
			}
		} catch (ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Given a target type for the result of an expression and the actual type of an
	 * expression, extract the target type for that expression. For example,
	 * consider this simple situation:
	 *
	 * <pre>
	 * {int:16 f}|null x = {f:1}
	 * </pre>
	 *
	 * The right-hand side is the expression of interest. The target type for this
	 * is <code>{int:16 f}|null</code>, whilst its actual type is just
	 * <code>{int f}</code>. Thus, the target type of the expression is
	 * <code>{int:16 f}</code>. The following illustrates another similar example:
	 *
	 * <pre>
	 * int:16[]|null x = [1]
	 * </pre>
	 *
	 * The right-hand side is the expression of interest. The target type for this
	 * is <code>int:16[]|null</code>, whilst its actual type is just
	 * <code>int[]</code>. Thus, the target array type is <code>int:16[]</code>.
	 *
	 * @param target
	 *            The overall target type for the expression in question
	 * @param actual
	 *            The stated result type for the expression in question. This is
	 *            necessary, for example, to determine which component of a union is
	 *            the actual target.
	 * @return
	 */
	public <T extends Type> T extractTargetType(Type target, T actual) {
		try {
			if (target.getClass() == actual.getClass()) {
				return (T) target;
			} else if (target instanceof Type.Union) {
				Type.Union type = (Type.Union) target;
				for (int i = 0; i != type.size(); ++i) {
					Type element = type.get(i);
					if (typeSystem.isRawCoerciveSubtype(element, actual, null)) {
						return extractTargetType(element, actual);
					}
				}
				throw new RuntimeException("deadcode reached");
			} else if (target instanceof Type.Nominal) {
				Type.Nominal type = (Type.Nominal) target;
				WhileyFile.Decl.Type decl = typeSystem.resolveExactly(type.getName(), WhileyFile.Decl.Type.class);
				return extractTargetType(decl.getType(), actual);
			} else {
				throw new UnsupportedOperationException("implement target array extraction (" + target + ")");
			}
		} catch (ResolutionError e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Given a type which is either an array or a union of arrays, extract the array
	 * type.
	 *
	 * @param type
	 * @return
	 */
	public List<LowLevel.Type.Array> extractArrayTypes(LowLevel.Type type) {
		ArrayList<LowLevel.Type.Array> types = new ArrayList<>();
		if (type instanceof LowLevel.Type.Array) {
			types.add((LowLevel.Type.Array) type);
		} else if (type instanceof LowLevel.Type.Union) {
			LowLevel.Type.Union ut = (LowLevel.Type.Union) type;
			for (int i = 0; i != ut.size(); ++i) {
				types.addAll(extractArrayTypes(ut.getElement(i)));
			}
		} else {
			throw new UnsupportedOperationException("Array type required");
		}
		return types;
	}




	/**
	 * Determine the maximum-width integer type for a bunch of integer types. For
	 * example, the max of <code>int:16</code> and <code>int:32</code> would be
	 * <code>int:32</code>. This is necessary to ensure that arithmetic operation on
	 * integers do not overflow.
	 *
	 * @param types
	 * @return
	 */
	public Type.Int max(Type.Int... types) {
		// FIXME: this needs to be updated when we have fixed-width integers.
		// NOTE: this could potentially just call extractIntegerType on a union of the
		// above.
		return Type.Int;
	}
}
