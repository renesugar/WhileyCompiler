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

package wycs.io;

import java.math.BigInteger;
import java.util.*;
import java.io.*;

import wyautl.util.BigRational;
import static wycs.io.Lexer.*;
import wybs.lang.Attribute;
import wybs.lang.SyntaxError;
import wybs.util.Pair;
import wycs.lang.*;

public class Parser {
	private String filename;
	private ArrayList<Token> tokens;		
	private int index;

	public Parser(String filename, List<Token> tokens) {
		this.filename = filename;
		this.tokens = new ArrayList<Token>(tokens);
	}
	
	public WycsFile parse() {
		ArrayList<Stmt> decls = new ArrayList<Stmt>();
		
		// first, strip out any whitespace
		for(int i=0;i!=tokens.size();) {
			Token lookahead = tokens.get(index);
			if (lookahead instanceof LineComment
				|| lookahead instanceof BlockComment) {
				tokens.remove(i);
			} else {
				i = i + 1;
			}
		}
		
		while (index < tokens.size()) {
			Token lookahead = tokens.get(index);
			if (lookahead instanceof Keyword
					&& lookahead.text.equals("assert")) {
				decls.add(parseAssert());					
			} else if(lookahead instanceof Keyword && lookahead.text.equals("define")) {
				decls.add(parseDefine());
			} else {
				syntaxError("unrecognised statement.",lookahead);
				return null;
			}
		}
		return new WycsFile(filename,decls);
	}	
	
	private Stmt.Assert parseAssert() {
		int start = index;
		matchKeyword("assert");
		String msg = null;
		if(index < tokens.size() && tokens.get(index) instanceof Lexer.Strung) {
			Strung s = match(Strung.class);
			msg = s.string;
		}
		Expr condition = parseTupleExpression();
		return Stmt.Assert(msg, condition, sourceAttr(start,
				index - 1));
	}
	
	private Stmt.Define parseDefine() {
		int start = index;
		matchKeyword("define");
		String name = matchIdentifier().text;
		match(LeftBrace.class);
		ArrayList<Pair<Type,String>> params = new ArrayList<Pair<Type,String>>();
		boolean firstTime=true;
		while(index < tokens.size() && !(tokens.get(index) instanceof RightBrace)) {
			if(!firstTime) {
				match(Comma.class);
			}
			firstTime=false;
			Type type = parseType();
			String param = matchIdentifier().text;
			params.add(new Pair(type,param));
		}
		match(RightBrace.class);
		matchKeyword("as");
		Expr condition = parseTupleExpression();
		return Stmt.Define(name,params,condition,sourceAttr(start,
				index - 1));
	}
	
	private Expr parseForall(int indent) {
		int start = index;
		matchKeyword("forall");
		return null;
	}
	
	private Expr parseTupleExpression() {
		int start = index;
		Expr e = parseCondition();		
		if (index < tokens.size() && tokens.get(index) instanceof Comma) {
			// this is a tuple constructor
			ArrayList<Expr> exprs = new ArrayList<Expr>();
			exprs.add(e);
			while (index < tokens.size() && tokens.get(index) instanceof Comma) {
				match(Comma.class);
				exprs.add(parseCondition());
				checkNotEof();
			}
			return new Expr.Nary(Expr.Nary.Op.TUPLE,exprs,sourceAttr(start,index-1));
		} else {
			return e;
		}
	}
	
	private Expr parseCondition() {
		checkNotEof();
		int start = index;		
		Expr c1 = parseAndOrCondition();				
		if(index < tokens.size() && tokens.get(index) instanceof LongArrow) {			
			match(LongArrow.class);
			
			
			Expr c2 = parseCondition();			
			return Expr.Binary(Expr.Binary.Op.IMPLIES, c1, c2, sourceAttr(start,
					index - 1));
		}
		
		return c1;
	}
	
	private Expr parseAndOrCondition() {
		checkNotEof();
		int start = index;		
		Expr c1 = parseConditionExpression();		

		if(index < tokens.size() && tokens.get(index) instanceof LogicalAnd) {			
			match(LogicalAnd.class);
			Expr c2 = parseAndOrCondition();			
			return Expr.Nary(Expr.Nary.Op.AND, new Expr[]{c1, c2}, sourceAttr(start,
					index - 1));
		} else if(index < tokens.size() && tokens.get(index) instanceof LogicalOr) {
			match(LogicalOr.class);
			Expr c2 = parseAndOrCondition();
			return Expr.Nary(Expr.Nary.Op.OR, new Expr[]{c1, c2}, sourceAttr(start,
					index - 1));			
		} 
		return c1;		
	}
		
	private Expr parseConditionExpression() {		
		int start = index;
						
		if (index < tokens.size() && tokens.get(index) instanceof ForAll) {
			match(ForAll.class);
			return parseQuantifier(start,true);			
		} else if (index < tokens.size() && tokens.get(index) instanceof Exists) {
			match(Exists.class);
			return parseQuantifier(start,false);			
		} 
		
		Expr lhs = parseAddSubExpression();
		
		if (index < tokens.size() && tokens.get(index) instanceof LessEquals) {
			match(LessEquals.class);				
			
			
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.LTEQ, lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof LeftAngle) {
 			match(LeftAngle.class);				
 			
 			
 			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.LT, lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof GreaterEquals) {
			match(GreaterEquals.class);	
						
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.GTEQ,  lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof RightAngle) {
			match(RightAngle.class);			
			
			
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.GT, lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof EqualsEquals) {
			match(EqualsEquals.class);			
			
			
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.EQ, lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof NotEquals) {
			match(NotEquals.class);			
			
			
			Expr rhs = parseAddSubExpression();			
			return Expr.Binary(Expr.Binary.Op.NEQ, lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof ElemOf) {
			match(ElemOf.class);			
						
			Expr rhs = parseAddSubExpression();			
			return Expr.Binary(Expr.Binary.Op.IN, lhs,  rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof Lexer.SubsetEquals) {
			match(Lexer.SubsetEquals.class);									
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.SUBSETEQ, lhs, rhs, sourceAttr(start,index-1));
		} else if (index < tokens.size() && tokens.get(index) instanceof Lexer.Subset) {
			match(Lexer.Subset.class);									
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.SUBSET, lhs,  rhs, sourceAttr(start,index-1));
		} else {
			return lhs;
		}	
	}
		
	private Expr parseAddSubExpression() {
		int start = index;
		Expr lhs = parseMulDivExpression();
		
		if (index < tokens.size() && tokens.get(index) instanceof Plus) {
			match(Plus.class);
			
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.ADD, lhs, rhs, sourceAttr(start,
					index - 1));
		} else if (index < tokens.size() && tokens.get(index) instanceof Minus) {
			match(Minus.class);
			
			
			Expr rhs = parseAddSubExpression();
			return Expr.Binary(Expr.Binary.Op.SUB, lhs, rhs, sourceAttr(start,
					index - 1));
		} 
		
		return lhs;
	}
	
	private Expr parseMulDivExpression() {
		int start = index;
		Expr lhs = parseIndexTerm();
		
		if (index < tokens.size() && tokens.get(index) instanceof Star) {
			match(Star.class);
			
			
			Expr rhs = parseMulDivExpression();
			return Expr.Binary(Expr.Binary.Op.MUL, lhs, rhs, sourceAttr(start,
					index - 1));
		} else if (index < tokens.size()
				&& tokens.get(index) instanceof RightSlash) {
			match(RightSlash.class);
			
			
			Expr rhs = parseMulDivExpression();
			return Expr.Binary(Expr.Binary.Op.DIV, lhs, rhs, sourceAttr(start,
					index - 1));
		}

		return lhs;
	}	
	
	private Expr parseIndexTerm() {
		checkNotEof();
		int start = index;
		int ostart = index;		
		Expr lhs = parseTerm();

		if(index < tokens.size()) {
			Token lookahead = tokens.get(index);

			while (lookahead instanceof LeftSquare) {
				start = index;
				if (lookahead instanceof LeftSquare) {
					match(LeftSquare.class);

					Expr rhs = parseAddSubExpression();

					match(RightSquare.class);
					lhs = Expr.Binary(Expr.Binary.Op.INDEXOF, lhs, rhs,
							sourceAttr(start, index - 1));
				}
				if (index < tokens.size()) {
					lookahead = tokens.get(index);
				} else {
					lookahead = null;
				}
			}
		}
		
		return lhs;		
	}
		
	private Expr parseTerm() {		
		checkNotEof();		
		
		int start = index;
		Token token = tokens.get(index);		
		
		if(token instanceof LeftBrace) {
			match(LeftBrace.class);
			
			checkNotEof();			
			Expr v = parseTupleExpression();			
			
			checkNotEof();
			token = tokens.get(index);			
			match(RightBrace.class);
			return v;			 		
		} else if (token.text.equals("null")) {
			matchKeyword("null");			
			return Expr.Constant(null,
					sourceAttr(start, index - 1));
		} else if (token.text.equals("true")) {
			matchKeyword("true");			
			return Expr.Constant(Value.Bool(true),
					sourceAttr(start, index - 1));
		} else if (token.text.equals("false")) {	
			matchKeyword("false");
			return Expr.Constant(Value.Bool(false),
					sourceAttr(start, index - 1));			
		} else if (token instanceof Identifier) {
			return parseVariableOrFunCall();
		} else if (token instanceof Int) {			
			BigInteger val = match(Int.class).value;
			return Expr.Constant(Value.Integer(val),
					sourceAttr(start, index - 1));
		} else if (token instanceof Real) {
			BigRational val = match(Real.class).value;
			return Expr.Constant(Value.Rational(val),
					sourceAttr(start, index - 1));
		} else if (token instanceof Minus) {
			return parseNegation();
		} else if (token instanceof Bar) {
			return parseLengthOf();
		} else if (token instanceof Shreak) {
			match(Shreak.class);
			return Expr.Unary(Expr.Unary.Op.NOT, parseTerm(), sourceAttr(
					start, index - 1));
		} else if (token instanceof LeftCurly) {
			return parseSet();
		} 
		syntaxError("unrecognised term.",token);
		return null;		
	}
	
	private Expr parseLengthOf() {
		int start = index;
		match(Bar.class);
		
		Expr e = parseIndexTerm();
		
		match(Bar.class);
		return Expr.Unary(Expr.Unary.Op.LENGTHOF,e, sourceAttr(start, index - 1));
	}
	
	private Expr parseSet() {
		int start = index;
		match(LeftCurly.class);
		ArrayList<Expr> elements = new ArrayList<Expr>();
		boolean firstTime=true;
		while(index < tokens.size() && !(tokens.get(index) instanceof RightCurly)) {
			if(!firstTime) {
				match(Comma.class);
			}
			firstTime=false;
			elements.add(parseCondition());
		}
		match(RightCurly.class);
		return Expr.Nary(Expr.Nary.Op.SET, elements, sourceAttr(start, index - 1));
	}
	
	private Expr parseVariableOrFunCall() {
		int start = index;
		String name = matchIdentifier().text;
		if(index < tokens.size() && tokens.get(index) instanceof LeftBrace) {
			match(LeftBrace.class);
			ArrayList<Expr> arguments = new ArrayList<Expr>();
			boolean firstTime=true;
			while(index < tokens.size() && !(tokens.get(index) instanceof RightBrace)) {
				if(!firstTime) {
					match(Comma.class);
				}
				firstTime=false;
				arguments.add(parseCondition());
			}
			match(RightBrace.class);
			return Expr.FunCall(name, arguments.toArray(new Expr[arguments.size()]),
					sourceAttr(start, index - 1));
		} else {
			return Expr.Variable(name, sourceAttr(start, index - 1));
		}
	}
	
	private Expr parseQuantifier(int start, boolean forall) {
		match(LeftSquare.class);
		
		ArrayList<Pair<Type,String>> variables = new ArrayList<Pair<Type,String>>();
		boolean firstTime = true;
		Token token = tokens.get(index);
		while (!(token instanceof Colon)) {
			if (!firstTime) {
				match(Comma.class);
				
			} else {
				firstTime = false;
			}			
			Type type = parseType();
			Identifier variable = matchIdentifier();
			variables.add(new Pair(type, variable.text));
			
			token = tokens.get(index);
		}
		match(Colon.class);
		Expr condition = parseTupleExpression();
		match(RightSquare.class);

		if (forall) {
			return Expr.ForAll(variables, condition, sourceAttr(start,
					index - 1));
		} else {
			return Expr.Exists(variables, condition, sourceAttr(start,
					index - 1));
		}
	}
		
	private Expr parseNegation() {
		int start = index;
		match(Minus.class);
		
		Expr e = parseIndexTerm();
		
		if (e instanceof Expr.Constant) {
			Expr.Constant c = (Expr.Constant) e;
			if (c.value instanceof Value.Integer) {
				Value.Integer i = (Value.Integer) c.value;
				java.math.BigInteger bi = (BigInteger) i.value;
				return Expr.Constant(Value.Integer(bi
						.negate()), sourceAttr(start, index));
			} else if (c.value instanceof Value.Rational) {
				Value.Rational r = (Value.Rational) c.value;
				BigRational br = (BigRational) r.value;
				return Expr.Constant(Value.Rational(br
						.negate()), sourceAttr(start, index));
			}
		}
		
		return Expr.Unary(Expr.Unary.Op.NEG, e, sourceAttr(start, index));		
	}
	
	private Type parseType() {				
		
		checkNotEof();
		int start = index;
		Token token = tokens.get(index);
		Type t;
		
		if(token.text.equals("any")) {
			matchKeyword("any");
			t = Type.Any;
		} else if(token.text.equals("int")) {
			matchKeyword("int");			
			t = Type.Int;
		} else if(token.text.equals("real")) {
			matchKeyword("real");
			t = Type.Real;
		} else if(token.text.equals("void")) {
			matchKeyword("void");
			t = Type.Void;
		} else if(token.text.equals("bool")) {
			matchKeyword("bool");
			t = Type.Bool;
		} else if (token instanceof LeftBrace) {
			match(LeftBrace.class);
			t = parseType();
			match(RightBrace.class);
		} else if(token instanceof Shreak) {
			match(Shreak.class);
			t = Type.Not(parseType());
		} else if (token instanceof LeftCurly) {		
			match(LeftCurly.class);
			t = Type.Set(parseType());
			match(RightCurly.class);
		} else {
			syntaxError("unknown type encountered",token);
			return null; // deadcode
		}
		
		if (index < tokens.size() && tokens.get(index) instanceof Comma) {
			// indicates a tuple
			ArrayList<Type> types = new ArrayList<Type>();
			types.add(t);
			while (index < tokens.size() && tokens.get(index) instanceof Comma) {
				match(Comma.class);
				types.add(parseType());
			}
			return Type.Tuple(types);
		}
		
		return t;
	}	
	
	private void checkNotEof() {		
		if (index >= tokens.size()) {
			throw new SyntaxError("unexpected end-of-file", filename,
					index - 1, index - 1);
		}
		return;
	}
	
	private <T extends Token> T match(Class<T> c) {
		checkNotEof();
		Token t = tokens.get(index);
		if (!c.isInstance(t)) {			
			syntaxError("syntax error" , t);
		}
		index = index + 1;
		return (T) t;
	}
	
	private Token matchAll(Class<? extends Token>... cs) {
		checkNotEof();
		Token t = tokens.get(index);
		for(Class<? extends Token> c : cs) {
			if (c.isInstance(t)) {			
				index = index + 1;
				return t;
			}
		}
		syntaxError("syntax error" , t);
		return null;
	}
	
	private Identifier matchIdentifier() {
		checkNotEof();
		Token t = tokens.get(index);
		if (t instanceof Identifier) {
			Identifier i = (Identifier) t;
			index = index + 1;
			return i;
		}
		syntaxError("identifier expected", t);
		return null; // unreachable.
	}
	
	private Keyword matchKeyword(String keyword) {
		checkNotEof();
		Token t = tokens.get(index);
		if (t instanceof Keyword) {
			if (t.text.equals(keyword)) {
				index = index + 1;
				return (Keyword) t;
			}
		}
		syntaxError("keyword " + keyword + " expected.", t);
		return null;
	}
	
	private Attribute.Source sourceAttr(int start, int end) {
		Token t1 = tokens.get(start);
		Token t2 = tokens.get(end);
		// HACK: should really calculate the line number correctly here.
		return new Attribute.Source(t1.start,t2.end(),0);
	}
	
	private void syntaxError(String msg, Expr e) {
		Attribute.Source loc = e.attribute(Attribute.Source.class);
		throw new SyntaxError(msg, filename, loc.start, loc.end);
	}

	private void syntaxError(String msg, Token t) {
		throw new SyntaxError(msg, filename, t.start, t.start
				+ t.text.length() - 1);
	}
}