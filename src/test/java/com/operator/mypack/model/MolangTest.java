package com.operator.mypack.model;

import com.operator.mypack.model.anim.Molang;
import com.operator.mypack.model.anim.MolangContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolangTest {

    private static double eval(String src) {
        return Molang.compile(src).eval(new MolangContext(new Random(1)));
    }

    private static double eval(String src, MolangContext ctx) {
        return Molang.compile(src).eval(ctx);
    }

    @Test
    @DisplayName("arithmetic follows normal precedence and associativity")
    void arithmetic() {
        assertEquals(14, eval("2 + 3 * 4"), 1e-9);
        assertEquals(20, eval("(2 + 3) * 4"), 1e-9);
        assertEquals(1, eval("10 - 5 - 4"), 1e-9, "left associative");
        assertEquals(2.5, eval("10 / 4"), 1e-9);
        assertEquals(1, eval("10 % 3"), 1e-9);
        assertEquals(-3, eval("-3"), 1e-9);
        assertEquals(2, eval("1 - -1"), 1e-9);
        assertEquals(0.5, eval(".5"), 1e-9);
        assertEquals(1.5, eval("1.5f"), 1e-9);
    }

    @Test
    @DisplayName("division and modulo by zero yield 0 instead of infinity/NaN")
    void divisionByZero() {
        assertEquals(0, eval("5 / 0"), 0);
        assertEquals(0, eval("5 % 0"), 0);
        assertEquals(0, eval("math.mod(5, 0)"), 0);
    }

    @Test
    @DisplayName("math trig functions work in degrees")
    void trigInDegrees() {
        assertEquals(1, eval("math.sin(90)"), 1e-9);
        assertEquals(0, eval("math.cos(90)"), 1e-9);
        assertEquals(0.5, eval("math.sin(30)"), 1e-9);
        assertEquals(30, eval("math.asin(0.5)"), 1e-9);
        assertEquals(45, eval("math.atan2(1, 1)"), 1e-9);
        assertEquals(Math.PI, eval("math.pi"), 1e-9);
    }

    @Test
    @DisplayName("common math functions")
    void mathFunctions() {
        assertEquals(3, eval("math.abs(-3)"), 1e-9);
        assertEquals(4, eval("math.ceil(3.2)"), 1e-9);
        assertEquals(3, eval("math.floor(3.9)"), 1e-9);
        assertEquals(4, eval("math.round(3.5)"), 1e-9);
        assertEquals(-3, eval("math.trunc(-3.9)"), 1e-9);
        assertEquals(8, eval("math.pow(2, 3)"), 1e-9);
        assertEquals(3, eval("math.sqrt(9)"), 1e-9);
        assertEquals(2, eval("math.min(5, 2)"), 1e-9);
        assertEquals(5, eval("math.max(5, 2)"), 1e-9);
        assertEquals(10, eval("math.clamp(15, 0, 10)"), 1e-9);
        assertEquals(0, eval("math.clamp(-5, 0, 10)"), 1e-9);
        assertEquals(5, eval("math.lerp(0, 10, 0.5)"), 1e-9);
        assertEquals(0.5, eval("math.hermite_blend(0.5)"), 1e-9);
        assertEquals(180, eval("math.lerprotate(170, -170, 0.5)"), 1e-9);
    }

    @Test
    @DisplayName("comparison, logic and ternary operators")
    void logic() {
        assertEquals(1, eval("3 > 2"), 0);
        assertEquals(0, eval("3 < 2"), 0);
        assertEquals(1, eval("3 >= 3 && 2 <= 2"), 0);
        assertEquals(1, eval("0 || 5"), 0);
        assertEquals(0, eval("!5"), 0);
        assertEquals(1, eval("1 == 1"), 0);
        assertEquals(1, eval("1 != 2"), 0);
        assertEquals(10, eval("1 > 0 ? 10 : 20"), 0);
        assertEquals(20, eval("1 < 0 ? 10 : 20"), 0);
        assertEquals(0, eval("1 < 0 ? 10"), 0, "missing else branch is 0");
        assertEquals(3, eval("0 ? 1 : 0 ? 2 : 3"), 0, "right associative chains");
    }

    @Test
    @DisplayName("queries, variables and statement lists with assignment and return")
    void variablesAndStatements() {
        MolangContext ctx = new MolangContext(new Random(1)).setQuery("anim_time", 2.0).setQuery("ground_speed", 3.0);
        assertEquals(2, eval("query.anim_time", ctx), 0);
        assertEquals(2, eval("q.anim_time", ctx), 0);
        assertEquals(6, eval("Query.Anim_Time * query.ground_speed", ctx), 0, "names are case-insensitive");
        assertEquals(0, eval("query.unknown_thing", ctx), 0, "unknown queries read as 0");
        assertEquals(15, eval("variable.a = 5; v.b = variable.a * 2; return v.a + v.b;", ctx), 0);
        assertEquals(5, ctx.variable("a"), 0);
        assertEquals(10, ctx.variable("b"), 0);
        assertEquals(7, eval("temp.x = 7; t.x", ctx), 0);
    }

    @Test
    @DisplayName("typical Blockbench keyframe expressions evaluate correctly")
    void realWorldExpressions() {
        MolangContext ctx = new MolangContext(new Random(1)).setQuery("anim_time", 1.0);
        assertEquals(10, eval("math.sin(query.anim_time * 90) * 10", ctx), 1e-9);
        assertEquals(4, eval("math.abs(math.sin(query.anim_time * 90)) * 4", ctx), 1e-9);
        assertEquals(0, eval("math.cos(q.anim_time * 90)", ctx), 1e-9);
        assertEquals(12.5, eval("math.sin(q.anim_time * 30) * 25", ctx), 1e-9);
    }

    @Test
    @DisplayName("math.random uses the injected generator and stays in range")
    void randomIsInjected() {
        double a = Molang.compile("math.random(5, 10)").eval(new MolangContext(new Random(42)));
        double b = Molang.compile("math.random(5, 10)").eval(new MolangContext(new Random(42)));
        assertEquals(a, b, 0, "same seed, same value");
        assertTrue(a >= 5 && a < 10);
        double i = Molang.compile("math.random_integer(1, 6)").eval(new MolangContext(new Random(3)));
        assertEquals(Math.rint(i), i, 0);
        assertTrue(i >= 1 && i <= 6);
    }

    @Test
    @DisplayName("syntax errors are reported with a position and never crash compileOrZero")
    void errors() {
        for (String bad : new String[]{"1 +", "(1", "math.nope(1)", "math.sin()", "foo.bar", "1 & 2", "1 $ 2", "variable.x = ;", "nothing"}) {
            assertThrows(Molang.CompileException.class, () -> Molang.compile(bad), bad);
        }
        List<String> errors = new ArrayList<>();
        assertEquals(0, Molang.compileOrZero("1 +", errors::add).eval(new MolangContext()), 0);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("molang '1 +'"));
        assertEquals(0, Molang.compile("").eval(new MolangContext()), 0);
        assertEquals(0, Molang.compile("   ").eval(new MolangContext()), 0);
    }
}
