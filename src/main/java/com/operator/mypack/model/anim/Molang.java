package com.operator.mypack.model.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A compact Molang interpreter: enough of the language for animation keyframes. Supported: numbers, arithmetic
 * ({@code + - * / %}), comparisons, {@code && || !}, the ternary operator, parentheses, {@code math.*} functions
 * (angles in degrees, like Bedrock), {@code query.*} / {@code q.*}, {@code variable.*} / {@code v.*} /
 * {@code temp.*} / {@code t.*} reads and assignments, and statement lists with {@code return}.
 */
public final class Molang {

    /** A compiled expression. */
    @FunctionalInterface
    public interface Expr {
        double eval(MolangContext ctx);
    }

    /** Raised for syntax errors; the message includes the position. */
    public static final class CompileException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CompileException(String message) {
            super(message);
        }
    }

    private Molang() {
    }

    public static Expr constant(double value) {
        return ctx -> value;
    }

    /** Compiles {@code source}; throws {@link CompileException} on malformed input. */
    public static Expr compile(String source) {
        if (source == null || source.isBlank()) {
            return constant(0.0D);
        }
        Parser parser = new Parser(source);
        Expr expr = parser.parseProgram();
        return expr;
    }

    /** Compiles {@code source}; on error reports once through {@code onError} and yields the constant 0. */
    public static Expr compileOrZero(String source, Consumer<String> onError) {
        try {
            return compile(source);
        } catch (CompileException e) {
            onError.accept("molang '" + source + "': " + e.getMessage());
            return constant(0.0D);
        }
    }

    // ------------------------------------------------------------------ tokenizer

    private enum T {
        NUM, IDENT, OP, LPAREN, RPAREN, COMMA, QUESTION, COLON, SEMI, EOF
    }

    private record Token(T type, String text, double number, int pos) {
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int start = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                if (i < s.length() && (s.charAt(i) == 'f' || s.charAt(i) == 'F')) {
                    i++; // Molang allows a trailing 'f' on literals
                }
                String text = s.substring(start, i).replaceAll("[fF]$", "");
                try {
                    out.add(new Token(T.NUM, text, Double.parseDouble(text), start));
                } catch (NumberFormatException e) {
                    throw new CompileException("bad number '" + text + "' at " + start);
                }
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(T.IDENT, s.substring(start, i).toLowerCase(Locale.ROOT), 0, start));
            } else {
                int start = i;
                switch (c) {
                    case '(' -> out.add(new Token(T.LPAREN, "(", 0, start));
                    case ')' -> out.add(new Token(T.RPAREN, ")", 0, start));
                    case ',' -> out.add(new Token(T.COMMA, ",", 0, start));
                    case '?' -> out.add(new Token(T.QUESTION, "?", 0, start));
                    case ':' -> out.add(new Token(T.COLON, ":", 0, start));
                    case ';' -> out.add(new Token(T.SEMI, ";", 0, start));
                    case '+', '-', '*', '/', '%' -> out.add(new Token(T.OP, String.valueOf(c), 0, start));
                    case '<', '>', '=', '!' -> {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                            out.add(new Token(T.OP, "" + c + '=', 0, start));
                            i++;
                        } else {
                            out.add(new Token(T.OP, String.valueOf(c), 0, start));
                        }
                    }
                    case '&' -> {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '&') {
                            out.add(new Token(T.OP, "&&", 0, start));
                            i++;
                        } else {
                            throw new CompileException("unexpected '&' at " + start);
                        }
                    }
                    case '|' -> {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '|') {
                            out.add(new Token(T.OP, "||", 0, start));
                            i++;
                        } else {
                            throw new CompileException("unexpected '|' at " + start);
                        }
                    }
                    default -> throw new CompileException("unexpected character '" + c + "' at " + start);
                }
                i++;
            }
        }
        out.add(new Token(T.EOF, "", 0, s.length()));
        return out;
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(String source) {
            this.tokens = tokenize(source);
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token next() {
            return tokens.get(index++);
        }

        private boolean isOp(String text) {
            Token t = peek();
            return t.type == T.OP && t.text.equals(text);
        }

        Expr parseProgram() {
            List<Expr> statements = new ArrayList<>();
            while (peek().type != T.EOF) {
                if (peek().type == T.SEMI) {
                    next();
                    continue;
                }
                statements.add(parseStatement());
                if (peek().type != T.SEMI && peek().type != T.EOF) {
                    throw new CompileException("unexpected '" + peek().text + "' at " + peek().pos);
                }
            }
            if (statements.isEmpty()) {
                return constant(0.0D);
            }
            if (statements.size() == 1) {
                return statements.get(0);
            }
            Expr[] all = statements.toArray(new Expr[0]);
            return ctx -> {
                double last = 0.0D;
                for (Expr statement : all) {
                    last = statement.eval(ctx);
                }
                return last;
            };
        }

        private Expr parseStatement() {
            Token t = peek();
            if (t.type == T.IDENT && t.text.equals("return")) {
                next();
                return parseExpression();
            }
            // assignment: <variable|temp>.name = expr
            if (t.type == T.IDENT && tokens.get(index + 1).type == T.OP && tokens.get(index + 1).text.equals("=")) {
                String name = t.text;
                String variable = assignableName(name);
                if (variable == null) {
                    throw new CompileException("cannot assign to '" + name + "' at " + t.pos);
                }
                next();
                next();
                Expr value = parseExpression();
                return ctx -> {
                    double v = value.eval(ctx);
                    ctx.setVariable(variable, v);
                    return v;
                };
            }
            return parseExpression();
        }

        private static String assignableName(String name) {
            for (String prefix : new String[]{"variable.", "v.", "temp.", "t."}) {
                if (name.startsWith(prefix) && name.length() > prefix.length()) {
                    return name.substring(prefix.length());
                }
            }
            return null;
        }

        Expr parseExpression() {
            Expr condition = parseOr();
            if (peek().type == T.QUESTION) {
                next();
                Expr whenTrue = parseExpression();
                Expr whenFalse = constant(0.0D);
                if (peek().type == T.COLON) {
                    next();
                    whenFalse = parseExpression();
                }
                Expr a = whenTrue;
                Expr b = whenFalse;
                return ctx -> condition.eval(ctx) != 0.0D ? a.eval(ctx) : b.eval(ctx);
            }
            return condition;
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (isOp("||")) {
                next();
                Expr l = left;
                Expr r = parseAnd();
                left = ctx -> (l.eval(ctx) != 0.0D || r.eval(ctx) != 0.0D) ? 1.0D : 0.0D;
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseEquality();
            while (isOp("&&")) {
                next();
                Expr l = left;
                Expr r = parseEquality();
                left = ctx -> (l.eval(ctx) != 0.0D && r.eval(ctx) != 0.0D) ? 1.0D : 0.0D;
            }
            return left;
        }

        private Expr parseEquality() {
            Expr left = parseRelational();
            while (isOp("==") || isOp("!=")) {
                boolean eq = next().text.equals("==");
                Expr l = left;
                Expr r = parseRelational();
                left = ctx -> (Math.abs(l.eval(ctx) - r.eval(ctx)) < 1e-9) == eq ? 1.0D : 0.0D;
            }
            return left;
        }

        private Expr parseRelational() {
            Expr left = parseAdditive();
            while (isOp("<") || isOp("<=") || isOp(">") || isOp(">=")) {
                String op = next().text;
                Expr l = left;
                Expr r = parseAdditive();
                left = switch (op) {
                    case "<" -> ctx -> l.eval(ctx) < r.eval(ctx) ? 1.0D : 0.0D;
                    case "<=" -> ctx -> l.eval(ctx) <= r.eval(ctx) ? 1.0D : 0.0D;
                    case ">" -> ctx -> l.eval(ctx) > r.eval(ctx) ? 1.0D : 0.0D;
                    default -> ctx -> l.eval(ctx) >= r.eval(ctx) ? 1.0D : 0.0D;
                };
            }
            return left;
        }

        private Expr parseAdditive() {
            Expr left = parseMultiplicative();
            while (isOp("+") || isOp("-")) {
                boolean add = next().text.equals("+");
                Expr l = left;
                Expr r = parseMultiplicative();
                left = add ? ctx -> l.eval(ctx) + r.eval(ctx) : ctx -> l.eval(ctx) - r.eval(ctx);
            }
            return left;
        }

        private Expr parseMultiplicative() {
            Expr left = parseUnary();
            while (isOp("*") || isOp("/") || isOp("%")) {
                String op = next().text;
                Expr l = left;
                Expr r = parseUnary();
                left = switch (op) {
                    case "*" -> ctx -> l.eval(ctx) * r.eval(ctx);
                    case "/" -> ctx -> {
                        double d = r.eval(ctx);
                        return d == 0.0D ? 0.0D : l.eval(ctx) / d;
                    };
                    default -> ctx -> {
                        double d = r.eval(ctx);
                        return d == 0.0D ? 0.0D : l.eval(ctx) % d;
                    };
                };
            }
            return left;
        }

        private Expr parseUnary() {
            if (isOp("-")) {
                next();
                Expr inner = parseUnary();
                return ctx -> -inner.eval(ctx);
            }
            if (isOp("+")) {
                next();
                return parseUnary();
            }
            if (isOp("!")) {
                next();
                Expr inner = parseUnary();
                return ctx -> inner.eval(ctx) == 0.0D ? 1.0D : 0.0D;
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            Token t = next();
            switch (t.type) {
                case NUM: {
                    double v = t.number;
                    return ctx -> v;
                }
                case LPAREN: {
                    Expr inner = parseExpression();
                    if (peek().type != T.RPAREN) {
                        throw new CompileException("expected ')' at " + peek().pos);
                    }
                    next();
                    return inner;
                }
                case IDENT:
                    return identifier(t);
                default:
                    throw new CompileException("unexpected '" + (t.type == T.EOF ? "end of input" : t.text) + "' at " + t.pos);
            }
        }

        private Expr identifier(Token t) {
            String name = t.text;
            if (peek().type == T.LPAREN) {
                next();
                List<Expr> args = new ArrayList<>();
                if (peek().type != T.RPAREN) {
                    do {
                        if (peek().type == T.COMMA) {
                            next();
                        }
                        args.add(parseExpression());
                    } while (peek().type == T.COMMA);
                }
                if (peek().type != T.RPAREN) {
                    throw new CompileException("expected ')' after arguments of " + name + " at " + peek().pos);
                }
                next();
                return call(name, args, t.pos);
            }
            for (String prefix : new String[]{"query.", "q."}) {
                if (name.startsWith(prefix)) {
                    String q = name.substring(prefix.length());
                    return ctx -> ctx.query(q);
                }
            }
            for (String prefix : new String[]{"variable.", "v.", "temp.", "t."}) {
                if (name.startsWith(prefix)) {
                    String v = name.substring(prefix.length());
                    return ctx -> ctx.variable(v);
                }
            }
            switch (name) {
                case "math.pi":
                    return constant(Math.PI);
                case "true":
                    return constant(1.0D);
                case "false":
                    return constant(0.0D);
                default:
                    if (name.startsWith("context.") || name.startsWith("c.") || name.startsWith("geometry.")
                            || name.startsWith("texture.") || name.startsWith("material.")) {
                        return constant(0.0D); // not available at runtime here; reads as 0
                    }
                    throw new CompileException("unknown identifier '" + name + "' at " + t.pos);
            }
        }

        private Expr call(String name, List<Expr> args, int pos) {
            String fn = name.startsWith("math.") ? name.substring(5) : null;
            if (fn == null) {
                throw new CompileException("unknown function '" + name + "' at " + pos);
            }
            Expr a = args.size() > 0 ? args.get(0) : null;
            Expr b = args.size() > 1 ? args.get(1) : null;
            Expr c = args.size() > 2 ? args.get(2) : null;
            switch (fn) {
                case "abs": need(name, args, 1, pos); return ctx -> Math.abs(a.eval(ctx));
                case "sin": need(name, args, 1, pos); return ctx -> Math.sin(Math.toRadians(a.eval(ctx)));
                case "cos": need(name, args, 1, pos); return ctx -> Math.cos(Math.toRadians(a.eval(ctx)));
                case "asin": need(name, args, 1, pos); return ctx -> Math.toDegrees(Math.asin(clampUnit(a.eval(ctx))));
                case "acos": need(name, args, 1, pos); return ctx -> Math.toDegrees(Math.acos(clampUnit(a.eval(ctx))));
                case "atan": need(name, args, 1, pos); return ctx -> Math.toDegrees(Math.atan(a.eval(ctx)));
                case "atan2": need(name, args, 2, pos); return ctx -> Math.toDegrees(Math.atan2(a.eval(ctx), b.eval(ctx)));
                case "ceil": need(name, args, 1, pos); return ctx -> Math.ceil(a.eval(ctx));
                case "floor": need(name, args, 1, pos); return ctx -> Math.floor(a.eval(ctx));
                case "round": need(name, args, 1, pos); return ctx -> (double) Math.round(a.eval(ctx));
                case "trunc": need(name, args, 1, pos); return ctx -> {
                    double v = a.eval(ctx);
                    return v < 0 ? Math.ceil(v) : Math.floor(v);
                };
                case "sqrt": need(name, args, 1, pos); return ctx -> Math.sqrt(Math.max(0.0D, a.eval(ctx)));
                case "exp": need(name, args, 1, pos); return ctx -> Math.exp(a.eval(ctx));
                case "ln": need(name, args, 1, pos); return ctx -> {
                    double v = a.eval(ctx);
                    return v <= 0 ? 0.0D : Math.log(v);
                };
                case "pow": need(name, args, 2, pos); return ctx -> Math.pow(a.eval(ctx), b.eval(ctx));
                case "min": need(name, args, 2, pos); return ctx -> Math.min(a.eval(ctx), b.eval(ctx));
                case "max": need(name, args, 2, pos); return ctx -> Math.max(a.eval(ctx), b.eval(ctx));
                case "mod": need(name, args, 2, pos); return ctx -> {
                    double d = b.eval(ctx);
                    return d == 0.0D ? 0.0D : a.eval(ctx) % d;
                };
                case "clamp": need(name, args, 3, pos); return ctx -> Math.max(b.eval(ctx), Math.min(c.eval(ctx), a.eval(ctx)));
                case "lerp": need(name, args, 3, pos); return ctx -> {
                    double from = a.eval(ctx);
                    return from + (b.eval(ctx) - from) * c.eval(ctx);
                };
                case "lerprotate": need(name, args, 3, pos); return ctx -> {
                    double from = a.eval(ctx);
                    double diff = ((b.eval(ctx) - from + 540.0D) % 360.0D + 360.0D) % 360.0D - 180.0D;
                    return from + diff * c.eval(ctx);
                };
                case "hermite_blend": need(name, args, 1, pos); return ctx -> {
                    double t = a.eval(ctx);
                    return 3 * t * t - 2 * t * t * t;
                };
                case "random": need(name, args, 2, pos); return ctx -> {
                    double lo = a.eval(ctx);
                    double hi = b.eval(ctx);
                    return lo + ctx.random().nextDouble() * (hi - lo);
                };
                case "random_integer": need(name, args, 2, pos); return ctx -> {
                    long lo = Math.round(a.eval(ctx));
                    long hi = Math.round(b.eval(ctx));
                    return hi <= lo ? (double) lo : (double) ctx.random().nextLong(lo, hi + 1);
                };
                default:
                    throw new CompileException("unknown function '" + name + "' at " + pos);
            }
        }

        private static void need(String name, List<Expr> args, int count, int pos) {
            if (args.size() != count) {
                throw new CompileException(name + " takes " + count + " argument(s) at " + pos);
            }
        }

        private static double clampUnit(double v) {
            return Math.max(-1.0D, Math.min(1.0D, v));
        }
    }
}
