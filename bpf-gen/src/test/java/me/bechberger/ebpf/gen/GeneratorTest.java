package me.bechberger.ebpf.gen;

import me.bechberger.ebpf.gen.Generator.Kind;
import me.bechberger.ebpf.gen.Generator.Type;
import me.bechberger.ebpf.gen.Generator.Type.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {

    private static final String PACKAGE = "me.bechberger.ebpf.runtime";
    public static final IntType UNSIGNED_INT = new IntType(KnownTypes.getKnowIntUnchecked("unsigned int"));

    private Generator gen;

    @BeforeEach
    public void setUp() {
        gen = new Generator(PACKAGE);
    }

    @EnabledIfSystemProperty(named = "os.name", matches = ".*linux.*")
    @Test
    public void dontCrashWhenLoadingAll() {
        gen.process();
    }

    @Test
    public void testVoidType() {
        var type = new VoidType();
        assertEquals("void", type.toTypeName(gen).toString());
        assertNull(type.toGenericTypeName(gen));
    }

    @Test
    public void testIntType() {
        var type = new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("int"));
        assertTypeEquals("int", "java.lang.Integer", type);
    }

    @Test
    public void testUnsignedIntType() {
        var type = new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("unsigned int"));
        assertTypeEquals("@Unsigned int", "java.lang. @Unsigned Integer", type);
    }

    @Test
    public void testUInt128Type() {
        var type = new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("__int128 unsigned"));
        assertTypeEquals("me.bechberger.ebpf.type.BPFType.BPFIntType. @Unsigned " + "Int128", "me.bechberger.ebpf" +
                ".type.BPFType.BPFIntType. @Unsigned " + "Int128", type);
        assertNull(type.toTypeSpec(gen));
    }

    @Test
    public void testVoidPtrType() {
        var type = new Generator.Type.PtrType(new VoidType());
        assertTypeEquals("Ptr<?>", type);
    }

    @Test
    public void testVoidPtrPtrType() {
        var type = new Generator.Type.PtrType(new Generator.Type.PtrType(new VoidType()));
        assertTypeEquals("Ptr<Ptr<?>>", type);
        assertNull(type.toTypeSpec(gen));
    }

    @Test
    public void testUnsignedIntPtrType() {
        var type = new Generator.Type.PtrType(new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("unsigned " +
                "int")));
        assertTypeEquals("Ptr<java.lang. @Unsigned Integer>", type);
        assertNull(type.toTypeSpec(gen));
    }

    @Test
    public void testIntArrayType() {
        var type = new Generator.Type.ArrayType(new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("unsigned " +
                "int")), 10);
        assertTypeEquals("@Unsigned int @Size(10) []", type);
        assertNull(type.toTypeSpec(gen));
    }

    @Test
    public void testIntPtrArrayType() {
        var type =
                new Generator.Type.ArrayType(new Generator.Type.PtrType(new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("unsigned int"))), 10);
        assertTypeEquals("Ptr<java.lang. @Unsigned Integer> @Size(10) []", type);
    }

    @Test
    public void testBasicStruct() {
        var type = new Generator.Type.StructType("foo", List.of(new Generator.Type.TypeMember("a", UNSIGNED_INT, 0)));
        assertTypeEquals("foo", type);
        assertEquals("""
                @Type(
                    noCCodeGeneration = true,
                    cType = "struct foo"
                )
                @NotUsableInJava
                public static class foo extends Struct {
                  public @Unsigned int a;
                }
                """, Objects.requireNonNull(type.toTypeSpec(gen)).toString());
    }

    @Test
    public void testBasicUnion() {
        var type = new Generator.Type.UnionType("foo", List.of(new Generator.Type.TypeMember("a", UNSIGNED_INT, 0)));
        assertTypeEquals("foo", type);
        assertEquals("""
                @Type(
                    noCCodeGeneration = true,
                    cType = "union foo"
                )
                @NotUsableInJava
                public static class foo extends Union {
                  public @Unsigned int a;
                }
                """, Objects.requireNonNull(type.toTypeSpec(gen)).toString());
    }

    @Test
    public void testNamedEnum() {
        var type = new Generator.Type.EnumType("foo", 4, true, List.of(new EnumMember("A", 0), new EnumMember("B", 1)
                , new EnumMember("C", 2)));
        assertTypeEquals("foo", type);
        assertEquals("""
                @Type(
                    noCCodeGeneration = true,
                    cType = "enum foo"
                )
                public enum foo implements Enum<foo>, TypedEnum<foo, java.lang. @Unsigned Integer> {
                  /**
                   * {@code A = 0}
                   */
                  @EnumMember(
                      value = 0L,
                      name = "A"
                  )
                  A,
                
                  /**
                   * {@code B = 1}
                   */
                  @EnumMember(
                      value = 1L,
                      name = "B"
                  )
                  B,
                
                  /**
                   * {@code C = 2}
                   */
                  @EnumMember(
                      value = 2L,
                      name = "C"
                  )
                  C
                }
                """.trim(), Objects.requireNonNull(type.toTypeSpec(gen)).toString().trim());
        assertEquals(0, type.toFieldSpecs(gen).size());
    }

    @Test
    public void testUnnamedEnum() {
        var type = new Generator.Type.EnumType(null, 4, true, List.of(new EnumMember("A", 42)));
        assertFalse(type.hasName());
        var fields = type.toFieldSpecs(gen);
        assertEquals(1, fields.size());
        assertEquals("public static final @Unsigned int A = 42;", fields.getFirst().toString().trim());
    }

    @Test
    public void testIntVarType() {
        var type = new Generator.Type.VarType("foo", UNSIGNED_INT);
        assertNull(type.toTypeSpec(gen));
        assertEquals("foo", type.name());
        var fields = type.toFieldSpecs(gen);
        assertEquals(1, fields.size());
        assertEquals("public static @Unsigned int foo;", fields.getFirst().toString().trim());
    }

    @Test
    public void testStructVarType() {
        var structType = new Generator.Type.StructType("foo", List.of(new Generator.Type.TypeMember("a", UNSIGNED_INT
                , 0)));
        var type = new Generator.Type.VarType("foo", structType);
        var fields = type.toFieldSpecs(gen);
        assertEquals(1, fields.size());
        assertEquals("public static foo foo;", fields.getFirst().toString().trim());
    }

    @Test
    public void testUnnamedUnionInsideStruct() {
        var intType = UNSIGNED_INT;
        var longType = new Generator.Type.IntType(KnownTypes.getKnownInt(64, true).orElseThrow());
        var unionType = new Generator.Type.UnionType(null, List.of(new Generator.Type.TypeMember("a", intType, 0),
                new Generator.Type.TypeMember("b", longType, 4 * 8)));
        var type = new Generator.Type.StructType("foo", List.of(new Generator.Type.TypeMember(null, unionType, 0),
                new Generator.Type.TypeMember("c", intType, 8 * 8)));
        assertEquals("""
                @Type(
                    noCCodeGeneration = true,
                    cType = "struct foo"
                )
                @NotUsableInJava
                public static class foo extends Struct {
                  @InlineUnion(-1)
                  public @Unsigned int a;
                                
                  @InlineUnion(-1)
                  public long b;
                                
                  public @Unsigned int c;
                }
                """, Objects.requireNonNull(type.toTypeSpec(gen)).toString());
    }

    @Test
    public void testFunction() {
        var intType = UNSIGNED_INT;
        var proto = new Generator.Type.FuncProtoType(List.of(new FuncParameter("a", intType), new FuncParameter("b",
                intType)), intType);
        var func = new Generator.Type.FuncType("foo", proto);
        assertEquals("""
                @NotUsableInJava
                @BuiltinBPFFunction
                public static @Unsigned int foo(@Unsigned int a, @Unsigned int b) {
                  throw new MethodIsBPFRelatedFunction();
                }
                """, func.toMethodSpec(gen).toString());
    }

    @Test
    public void testEscapeKeywordNamedParameter() {
        var intType = UNSIGNED_INT;
        var proto = new Generator.Type.FuncProtoType(List.of(new FuncParameter("default", intType)), intType);
        var func = new Generator.Type.FuncType("foo", proto);
        assertEquals("""
                @NotUsableInJava
                @BuiltinBPFFunction
                public static @Unsigned int foo(@Unsigned int _default) {
                  throw new MethodIsBPFRelatedFunction();
                }
                """, func.toMethodSpec(gen).toString());
    }

    @Test
    public void testFunctionWithConstIntParameter() {
        var intType = UNSIGNED_INT;
        var proto = new Generator.Type.FuncProtoType(List.of(new FuncParameter("a", new MirrorType(Kind.CONST,
                intType))), intType);
        var func = new Generator.Type.FuncType("foo", proto);
        assertEquals("""
                @NotUsableInJava
                @BuiltinBPFFunction("foo((const unsigned int)$arg1)")
                public static @Unsigned int foo(@Unsigned int a) {
                  throw new MethodIsBPFRelatedFunction();
                }
                """, func.toMethodSpec(gen).toString());
    }

    @Test
    public void testCharPtrIsConvertedToString() {
        var charType = new Generator.Type.IntType(KnownTypes.getKnowIntUnchecked("char"));
        var ptrType = new Generator.Type.PtrType(charType);
        assertEquals("String", ptrType.toTypeName(gen).toString());
    }

    void assertTypeEquals(String expected, Type type) {
        java.lang.@me.bechberger.ebpf.annotations.Unsigned Integer i = 0;
        assertEquals(expected, type.toTypeName(gen).toString());
        assertEquals(expected, Objects.requireNonNull(type.toGenericTypeName(gen)).toString());
    }

    void assertTypeEquals(String expected, String expectedGeneric, Type type) {
        java.lang.@me.bechberger.ebpf.annotations.Unsigned Integer i = 0;
        assertEquals(expected, type.toTypeName(gen).toString());
        assertEquals(expectedGeneric, Objects.requireNonNull(type.toGenericTypeName(gen)).toString());
    }
}