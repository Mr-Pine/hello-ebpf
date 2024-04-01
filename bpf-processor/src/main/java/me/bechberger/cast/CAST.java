package me.bechberger.cast;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents an abstract syntax tree for C,
 * loosely based on the grammar from <a href="https://www.lysator.liu.se/c/ANSI-C-grammar-y.html">lysator.liu.se</a>,
 * for generating eBPF C programs.
 * <p>
 * Example: {@snippet :
 *  variableDefinition(struct(variable("myStruct"),
 *       List.of(
 *           structMember(Declarator.identifier("int"), variable("b")))
 *       ), variable("myVar", sec("a"))
 *  )
 * }
 */
public interface CAST {

    List<? extends CAST> children();

    Statement toStatement();

    sealed interface Expression extends CAST permits Declarator, InitDeclarator, Initializer, OperatorExpression, PrimaryExpression {

        @Override
        List<? extends Expression> children();

        @Override
        default Statement toStatement() {
            return Statement.expression(this);
        }

        static PrimaryExpression.Constant constant(Object value) {
            return new PrimaryExpression.Constant(value);
        }

        static PrimaryExpression.Variable variable(String name) {
            return new PrimaryExpression.Variable(name);
        }

        static PrimaryExpression.Variable variable(String name, PrimaryExpression.CAnnotation... annotations) {
            return new PrimaryExpression.Variable(name, annotations);
        }

        static PrimaryExpression.ParenthesizedExpression parenthesizedExpression(Expression expression) {
            return new PrimaryExpression.ParenthesizedExpression(expression);
        }

        static PrimaryExpression.EnumerationConstant enumerationConstant(String name) {
            return new PrimaryExpression.EnumerationConstant(name);
        }

        static PrimaryExpression.VerbatimExpression verbatim(String code) {
            return new PrimaryExpression.VerbatimExpression(code);
        }
    }

    /**
     * {@snippet :
     * primary_expression
     * 	: IDENTIFIER
     * 	| constant
     * 	| string
     * 	| '(' expression ')'
     * 	;
     *}
     */
    sealed interface PrimaryExpression extends Expression {

        @Override
        default List<? extends Expression> children() {
            return List.of();
        }

        @Override
        String toString();

        /**
         * Annotation like <code>@SEC("...")</code>
         *
         * @param annotation
         * @param value
         */
        record CAnnotation(String annotation, String value) implements CAST {
            @Override
            public List<? extends CAST> children() {
                return List.of();
            }

            @Override
            public String toString() {
                return annotation + "(" + Expression.constant(value) + ")";
            }

            static CAnnotation annotation(String annotation, String value) {
                return new CAnnotation(annotation, value);
            }

            static CAnnotation sec(String value) {
                return new CAnnotation("SEC", value);
            }

            @Override
            public Statement toStatement() {
                throw new UnsupportedOperationException("CAnnotation cannot be converted to a statement");
            }
        }

        /**
         * Variable name for expressions
         */
        record Variable(String name, CAnnotation... annotations) implements PrimaryExpression {
            @Override
            public String toString() {
                String annString = Arrays.stream(annotations).map(Object::toString).collect(Collectors.joining(" "));
                return name + (annString.isEmpty() ? "" : " " + annString);
            }
        }

        record EnumerationConstant(String name) implements PrimaryExpression {
            @Override
            public String toString() {
                return name;
            }
        }

        /**
         * Constant value for expressions, escapes string
         */
        record Constant(Object value) implements PrimaryExpression {
            @Override
            public String toString() {
                if (value instanceof String) {
// Escape the string
                    return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                } else {
                    return value.toString();
                }
            }
        }

        /**
         * Wraps an expression in parentheses
         */
        record ParenthesizedExpression(Expression expression) implements PrimaryExpression {
            @Override
            public String toString() {
                return "(" + expression + ")";
            }

            @Override
            public List<? extends Expression> children() {
                return List.of(expression);
            }
        }

        record VerbatimExpression(String code) implements PrimaryExpression {
            @Override
            public List<? extends PrimaryExpression> children() {
                return List.of();
            }

            @Override
            public String toString() {
                return code;
            }
        }
    }

    /**
     * Operators with precedence and associativity,
     * based on <a href="https://en.cppreference.com/w/cpp/language/operator_precedence">cppreference.com</a>
     */
    enum Operator {
        SUFFIX_INCREMENT("++", 2), SUFFIX_DECREMENT("--", 2), FUNCTION_CALL("()", 2), SUBSCRIPT("[]", 2),
        MEMBER_ACCESS(".", 2), POSTFIX_INCREMENT("++", 3), POSTFIX_DECREMENT("--", 3), UNARY_PLUS("+", 3),
        UNARY_MINUS("-", 3), LOGICAL_NOT("!", 3), BITWISE_NOT("~", 3), DEREFERENCE("*", 3), ADDRESS_OF("&", 3),
        SIZEOF("sizeof", 3), CAST("cast", 3), MULTIPLICATION("*", 5), DIVISION("/", 5), MODULUS("%", 5), ADDITION("+"
                , 6), SUBTRACTION("-", 6), SHIFT_LEFT("<<", 7), SHIFT_RIGHT(">>", 7), LESS_THAN("<", 9),
        LESS_THAN_OR_EQUAL("<=", 9), GREATER_THAN(">", 9), GREATER_THAN_OR_EQUAL(">=", 9), EQUAL("==", 10),
        NOT_EQUAL("!=", 10), BITWISE_AND("&", 11), BITWISE_XOR("^", 12), BITWISE_OR("|", 13), LOGICAL_AND("&&", 14),
        LOGICAL_OR("||", 15), CONDITIONAL("?", 16), ASSIGNMENT("=", 16), MULTIPLICATION_ASSIGNMENT("*=", 16),
        DIVISION_ASSIGNMENT("/=", 16), MODULUS_ASSIGNMENT("%=", 16), ADDITION_ASSIGNMENT("+=", 16),
        SUBTRACTION_ASSIGNMENT("-=", 16), SHIFT_LEFT_ASSIGNMENT("<<=", 16), SHIFT_RIGHT_ASSIGNMENT(">>=", 16),
        BITWISE_AND_ASSIGNMENT("&=", 16), BITWISE_XOR_ASSIGNMENT("^=", 16), BITWISE_OR_ASSIGNMENT("|=", 16), COMMA(","
                , 17);

        private static final Map<String, Operator> OPERATORS = new HashMap<>();
        private static final Map<String, Operator> ASSIGNMENT_OPERATORS = new HashMap<>();
        private static final Map<String, Operator> UNARY_OPERATORS = new HashMap<>();
        private static final Map<String, Operator> BINARY_OPERATORS = new HashMap<>();
        private static final Map<String, Operator> POSTFIX_OPERATORS = new HashMap<>();

        static {
// sort all operators into their respective maps
            for (Operator op : Operator.values()) {
                OPERATORS.put(op.op, op);
                if (op.op.endsWith("=")) {
                    ASSIGNMENT_OPERATORS.put(op.op, op);
                } else if (op.precedence == 3) {
                    UNARY_OPERATORS.put(op.op, op);
                } else if (op.precedence == 2) {
                    POSTFIX_OPERATORS.put(op.op, op);
                } else {
                    BINARY_OPERATORS.put(op.op, op);
                }
            }
        }

        public enum Associativity {
            LEFT, RIGHT
        }

        public final String op;
        public final int precedence;

        public final Associativity associativity;

        Operator(String op, int precedence) {
            this.op = op;
            this.precedence = precedence;
            if (precedence == 3 || precedence == 16) {
                this.associativity = Associativity.RIGHT;
            } else {
                this.associativity = Associativity.LEFT;
            }
        }

        public boolean isUnitary() {
            return precedence == 3 || precedence == 2;
        }

        @Override
        public String toString() {
            return op;
        }

        static Operator binary(String op) {
            return BINARY_OPERATORS.get(op);
        }

        static Operator unary(String op) {
            return UNARY_OPERATORS.get(op);
        }

        static Operator postfix(String op) {
            return POSTFIX_OPERATORS.get(op);
        }


        static Operator assignment(String op) {
            return ASSIGNMENT_OPERATORS.get(op);
        }

        static Operator fromString(String op) {
            return OPERATORS.get(op);
        }
    }

    record OperatorExpression(Operator operator, Expression... expressions) implements Expression {

        @Override
        public List<? extends Expression> children() {
            return Arrays.asList(expressions);
        }

        /**
         * Takes care of operator precedence and associativity
         */
        @Override
        public String toString() {
// idea:
// if the operator is a binary operator, we need to check the precedence of the children
// if the precedence of the child is lower, we need to wrap it in parentheses
// if the precedence is the same, we need to check if the operator is left or right associative
// if it is left associative, we need to wrap the left child in parentheses
// if it is right associative, we need to wrap the right child in parentheses
// if the operator is a unary operator, we need to check if the child is a binary operator
// if the child is a binary operator, we need to wrap it in parentheses
// if the operator is a postfix operator, we need to wrap the child in parentheses
// if the operator is an assignment operator, we need to wrap the left child in parentheses
// if the operator is a ternary operator, we need to wrap the children in parentheses
// if the operator is a member access operator, we need to wrap the right child in parentheses
// if the operator is a subscript operator, we need to wrap the right child in parentheses
// if the operator is a function call operator, we need to wrap the left child in parentheses
// if the operator is a dereference operator, we need to wrap the child in parentheses
// if the operator is an address of operator, we need to wrap the child in parentheses
// if the operator is a sizeof operator, we need to wrap the child in parentheses
// if the operator is a logical not operator, we need to wrap the child in parentheses
// if the operator is a bitwise not operator, we need to wrap the child in parentheses
// if the operator is a unary plus operator, we need to wrap the child in parentheses
// if the operator is a unary minus operator, we need to wrap the child in parentheses
// if the operator is a suffix increment operator, we need to wrap the child in parentheses
// if the operator is a suffix decrement operator, we need to wrap the child in parentheses
// if the operator is a prefix increment operator, we need to wrap the child in parentheses
// if the operator is a prefix decrement operator, we need to wrap the child in parentheses
// if the operator is a bitwise and operator, we need to wrap the children in parentheses

// if the operator is a binary operator, we need to check the precedence of the children

// if the operator is a unary operator, we need to check if the child is a binary operator
            if (operator().precedence == 3) {
                Expression operator1 = children().getFirst();
                String op1String = operator1.toString();
                if (operator1 instanceof OperatorExpression operatorExpression) {
                    if (operatorExpression.operator().precedence < operator().precedence) {
                        op1String = "(" + operatorExpression + ")";
                    }
                }
                return operator() + op1String;
            } else {
// if the operator is a postfix operator, we need to wrap the child in parentheses
                if (operator().precedence == 2) {
                    Expression operator1 = children().getFirst();
                    String op1String = operator1.toString();
                    if (operator1 instanceof OperatorExpression operatorExpression) {
                        op1String = "(" + operatorExpression + ")";
                    }
                    return op1String + operator();
                } else {
// if the operator is an assignment operator, we need to wrap the left child in parentheses
                    if (operator().precedence == 16) {
                        Expression operator1 = children().get(0);
                        Expression operator2 = children().get(1);
                        String op1String = operator1.toString();
                        String op2String = operator2.toString();
                        if (operator1 instanceof OperatorExpression operatorExpression) {
                            op1String = "(" + operatorExpression + ")";
                        }
                        if (operator2 instanceof OperatorExpression operatorExpression) {
                            if (operatorExpression.operator().precedence < operator().precedence) {
                                op2String = "(" + operatorExpression + ")";
                            }
                        }
                        return op1String + " " + operator() + " " + op2String;
                    } else {
// if the operator is a ternary operator, we need to wrap the children in parentheses
                        if (operator() == Operator.CONDITIONAL) {
                            Expression operator1 = children().get(0);
                            Expression operator2 = children().get(1);
                            Expression operator3 = children().get(2);
                            String op1String = operator1.toString();
                            String op2String = operator2.toString();
                            String op3String = operator3.toString();
                            if (operator1 instanceof OperatorExpression operatorExpression) {
                                op1String = "(" + operatorExpression + ")";
                            }
                            if (operator2 instanceof OperatorExpression operatorExpression) {
                                op2String = "(" + operatorExpression + ")";
                            }
                            if (operator3 instanceof OperatorExpression operatorExpression) {
                                op3String = "(" + operatorExpression + ")";
                            }
                            return op1String + " ? " + op2String + " : " + op3String;
                        } else if (operator() == Operator.MEMBER_ACCESS) {
                            Expression operator1 = children().get(0);
                            Expression operator2 = children().get(1);
                            String op1String = operator1.toString();
                            String op2String = operator2.toString();
                            if (operator2 instanceof OperatorExpression operatorExpression) {
                                op2String = "(" + operatorExpression + ")";
                            }
                            return op1String + "." + op2String;
                        } else if (operator() == Operator.SUBSCRIPT) {
                            Expression operator1 = children().get(0);
                            Expression operator2 = children().get(1);
                            String op1String = operator1.toString();
                            String op2String = operator2.toString();
                            if (operator2 instanceof OperatorExpression operatorExpression) {
                                op2String = "(" + operatorExpression + ")";
                            }
                            return op1String + "[" + op2String + "]";
                        } else if (operator() == Operator.FUNCTION_CALL) {
                            Expression func = children().getFirst();
                            String funcString = func.toString();
                            if (func instanceof OperatorExpression operatorExpression) {
                                funcString = "(" + funcString + ")";
                            }
                            return funcString + "(" + children().stream().skip(1).map(Object::toString).collect(Collectors.joining(", ")) + ")";
                        } else if (operator() == Operator.SIZEOF) {
                            Expression operator1 = children().getFirst();
                            String op1String = operator1.toString();
                            if (operator1 instanceof OperatorExpression operatorExpression) {
                                op1String = "(" + operatorExpression + ")";
                            }
                            return "sizeof(" + op1String + ")";
                        } else if (operator() == Operator.CAST) {
                            return "(" + children().get(0) + ")" + children().get(1);
                        } else if (operator().isUnitary()) {
                            Expression operator1 = children().getFirst();
                            String op1String = operator1.toString();
                            if (operator1 instanceof OperatorExpression operatorExpression) {
                                op1String = "(" + operatorExpression + ")";
                            }
                            if (operator().associativity == Operator.Associativity.RIGHT) {
                                return operator() + op1String;
                            } else {
                                return op1String + operator();
                            }
                        } else {
                            Expression operator1 = children().get(0);
                            Expression operator2 = children().get(1);
                            String op1String = operator1.toString();
                            String op2String = operator2.toString();
                            if (operator1 instanceof OperatorExpression operatorExpression) {
                                if (operatorExpression.operator().precedence < operator().precedence) {
                                    op1String = "(" + operatorExpression + ")";
                                } else if (operatorExpression.operator().precedence == operator().precedence) {
                                    if (operatorExpression.operator().associativity == Operator.Associativity.LEFT) {
                                        op1String = "(" + operatorExpression + ")";
                                    }
                                }
                            }
                            if (operator2 instanceof OperatorExpression operatorExpression) {
                                if (operatorExpression.operator().precedence < operator().precedence) {
                                    op2String = "(" + operatorExpression + ")";
                                } else if (operatorExpression.operator().precedence == operator().precedence) {
                                    if (operatorExpression.operator().associativity == Operator.Associativity.RIGHT) {
                                        op2String = "(" + operatorExpression + ")";
                                    }
                                }
                            }
                            return op1String + " " + operator() + " " + op2String;
                        }
                    }

                }
            }
        }

        public static OperatorExpression binary(String op, Expression left, Expression right) {
            return new OperatorExpression(Operator.binary(op), left, right);
        }

        public static OperatorExpression unary(String op, Expression expression) {
            return new OperatorExpression(Operator.unary(op), expression);
        }

        public static OperatorExpression postfix(String op, Expression expression) {
            return new OperatorExpression(Operator.postfix(op), expression);
        }

        public static OperatorExpression assignment(String op, Expression left, Expression right) {
            return new OperatorExpression(Operator.assignment(op), left, right);
        }

        public static OperatorExpression ternary(Expression condition, Expression trueExpression,
                                                 Expression falseExpression) {
            return new OperatorExpression(Operator.CONDITIONAL, condition, trueExpression, falseExpression);
        }

        public static OperatorExpression memberAccess(Expression left, Expression right) {
            return new OperatorExpression(Operator.MEMBER_ACCESS, left, right);
        }

        public static OperatorExpression arrayAccess(Expression left, Expression right) {
            return new OperatorExpression(Operator.SUBSCRIPT, left, right);
        }

        public static OperatorExpression call(Expression func, Expression... args) {
            return new OperatorExpression(Operator.FUNCTION_CALL,
                    Stream.concat(Stream.of(func), Arrays.stream(args)).toArray(Expression[]::new));
        }

        public static OperatorExpression pointer(Expression expression) {
            return new OperatorExpression(Operator.DEREFERENCE, expression);
        }

        public static OperatorExpression cast(PrimaryExpression.Variable type, Expression expression) {
            return new OperatorExpression(Operator.CAST, type, expression);
        }
    }

    record InitDeclarator(@Nullable PrimaryExpression.Variable name, Expression expression) implements Expression {

        @Override
        public List<? extends Expression> children() {
            return List.of(expression);
        }

        @Override
        public String toString() {
            return (name == null ? "" : name + " = ") + expression;
        }
    }

    sealed interface Initializer extends Expression {

        record InitializerList(List<InitDeclarator> declarators) implements Initializer {
            @Override
            public List<? extends Expression> children() {
                return declarators;
            }

            @Override
            public String toString() {
                return "{" + declarators.stream().map(InitDeclarator::toString).collect(Collectors.joining(", ")) + "}";
            }
        }
    }

    sealed interface Declarator extends Expression {

        default String toPrettyString(String indent, String increment) {
            return indent + this;
        }

        default String toPrettyString() {
            return toPrettyString("", "  ");
        }

        record PointerDeclarator(Declarator declarator) implements Declarator {
            @Override
            public List<? extends Expression> children() {
                return List.of(declarator);
            }

            @Override
            public String toString() {
                return "*" + declarator;
            }
        }

        record ArrayDeclarator(Declarator declarator, @Nullable Expression size) implements Declarator {
            @Override
            public List<? extends Expression> children() {
                return size == null ? List.of(declarator) : List.of(declarator, size);
            }

            @Override
            public String toString() {
                return declarator + (size == null ? "[]" : "[" + size + "]");
            }
        }

        /**
         * Struct member with optional size for ebpf member declaration (e.g. <code>u32 (var, 10)</code>)
         */
        record StructMember(Declarator declarator, PrimaryExpression.Variable name,
                            @Nullable PrimaryExpression ebpfSize) implements Declarator {

            @Override
            public List<? extends Expression> children() {
                return List.of(declarator, name);
            }

            @Override
            public String toString() {
                return declarator + " " + name;
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                if (ebpfSize == null) {
                    return declarator.toPrettyString(indent, increment) + " " + name + ";";
                }
                return indent + declarator + " (" + name + ", " + ebpfSize + ");";
            }
        }

        record StructDeclarator(@Nullable PrimaryExpression.Variable name,
                                List<StructMember> members) implements Declarator {
            @Override
            public List<? extends Expression> children() {
                if (name == null) {
                    return members;
                }
                return Stream.concat(Stream.of(name), members.stream()).collect(Collectors.toList());
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "struct " + (name == null ? "" : name + " ") + "{\n" + members.stream().map(m -> m.toPrettyString(indent + increment, increment)).collect(Collectors.joining("\n")) + "\n" + indent + "}";
            }
        }

        record FunctionDeclarator(Declarator declarator, List<Declarator> parameters) implements Declarator {
            @Override
            public List<? extends Expression> children() {
                return parameters;
            }

            @Override
            public String toString() {
                return declarator + "(" + parameters.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
            }
        }

        record IdentifierDeclarator(PrimaryExpression.Variable name) implements Declarator {
            @Override
            public List<? extends Expression> children() {
                return List.of(name);
            }

            @Override
            public String toString() {
                return name.toString();
            }
        }

        record StructIdentifierDeclarator(PrimaryExpression.Variable name) implements Declarator {
            @Override
            public List<? extends Expression> children() {
                return List.of(name);
            }

            @Override
            public String toString() {
                return "struct " + name.toString();
            }
        }

        static Declarator pointer(Declarator declarator) {
            return new PointerDeclarator(declarator);
        }

        static Declarator array(Declarator declarator, @Nullable Expression size) {
            return new ArrayDeclarator(declarator, size);
        }

        static Declarator function(Declarator declarator, List<Declarator> parameters) {
            return new FunctionDeclarator(declarator, parameters);
        }

        static Declarator identifier(PrimaryExpression.Variable name) {
            return new IdentifierDeclarator(name);
        }

        static Declarator identifier(String name) {
            return new IdentifierDeclarator(new PrimaryExpression.Variable(name));
        }

        static Declarator struct(PrimaryExpression.Variable name, List<StructMember> members) {
            return new StructDeclarator(name, members);
        }

        static StructMember structMember(Declarator declarator, PrimaryExpression.Variable name) {
            return new StructMember(declarator, name, null);
        }

        static StructMember structMember(Declarator declarator, PrimaryExpression.Variable name,
                                         PrimaryExpression ebpfSize) {
            return new StructMember(declarator, name, ebpfSize);
        }

        static Declarator structIdentifier(PrimaryExpression.Variable name) {
            return new StructIdentifierDeclarator(name);
        }
    }


    interface Statement extends CAST {

        /**
         * Pretty string representation of the AST
         *
         * @param indent    indent of the current line, ignored by expressions
         * @param increment increment of the indent
         * @return pretty string representation of the AST
         */
        default String toPrettyString(String indent, String increment) {
            return indent + this.children().getFirst() + ";";
        }

        default String toPrettyString() {
            return toPrettyString("", "  ");
        }

        @Override
        default Statement toStatement() {
            return this;
        }

        record ExpressionStatement(Expression expression) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(expression);
            }

            @Override
            public String toString() {
                return toPrettyString("", "");
            }
        }

        record VariableDefinition(Declarator type, PrimaryExpression.Variable name) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(type, name);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return type.toPrettyString(indent, increment) + " " + name + ";";
            }

        }

        record CompoundStatement(List<Statement> statements) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return statements;
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "{\n" + statements.stream().map(s -> s.toPrettyString(indent + increment, increment)).collect(Collectors.joining("\n")) + "\n" + indent + "}";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record IfStatement(Expression condition, Statement thenStatement,
                           @Nullable Statement elseStatement) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return elseStatement == null ? List.of(condition, thenStatement) : List.of(condition, thenStatement,
                        elseStatement);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "if (" + condition + ")\n" + thenStatement.toPrettyString(indent + increment,
                        increment) + (elseStatement == null ? "" :
                        " else\n" + elseStatement.toPrettyString(indent + increment, increment));
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record WhileStatement(Expression condition, Statement body) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(condition, body);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "while (" + condition + ")\n" + body.toPrettyString(indent + increment, increment);
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record ForStatement(@Nullable Expression init, @Nullable Expression condition, @Nullable Expression increment,
                            Statement body) implements Statement {

            @Override
            public List<? extends CAST> children() {
                List<CAST> children = new ArrayList<>();
                if (init != null) {
                    children.add(init);
                }
                if (condition != null) {
                    children.add(condition);
                }
                if (increment != null) {
                    children.add(increment);
                }
                children.add(body);
                return children;
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "for (" + (init == null ? "" : init) + "; " + (condition == null ? "" : condition) +
                        "; " + (increment == null ? "" : increment) + ")\n" + body.toPrettyString(indent + increment,
                        increment);
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record ReturnStatement(@Nullable Expression expression) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return expression == null ? List.of() : List.of(expression);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "return" + (expression == null ? "" : " " + expression) + ";";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record BreakStatement() implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of();
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "break;";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record ContinueStatement() implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of();
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "continue;";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record EmptyStatement() implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of();
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + ";";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record DeclarationStatement(Declarator declarator, @Nullable Initializer initializer) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return initializer == null ? List.of(declarator) : List.of(declarator, initializer);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + declarator + (initializer == null ? "" : " = " + initializer) + ";";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record StructDeclarationStatement(Declarator.StructDeclarator declarator) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(declarator);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + declarator + ";";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record FunctionDeclarationStatement(Declarator.FunctionDeclarator declarator,
                                            CompoundStatement body) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(declarator, body);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + declarator + "\n" + body.toPrettyString(indent + increment, increment);
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record Define(String name, PrimaryExpression.Constant value) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(value);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "#define " + name + " " + value;
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record Include(String file) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of();
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "#include " + file;
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record Typedef(Declarator declarator, PrimaryExpression.Variable name) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(declarator, name);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "typedef " + declarator + " " + name + ";";
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record CaseStatement(Expression expression, Statement body) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(expression, body);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "case " + expression + ":\n" + body.toPrettyString(indent + increment, increment);
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record DefaultStatement(Statement body) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(body);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "default:\n" + body.toPrettyString(indent + increment, increment);
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        record SwitchStatement(Expression expression, Statement body) implements Statement {

            @Override
            public List<? extends CAST> children() {
                return List.of(expression, body);
            }

            @Override
            public String toPrettyString(String indent, String increment) {
                return indent + "switch (" + expression + ")\n" + body.toPrettyString(indent + increment, increment);
            }

            @Override
            public String toString() {
                return toPrettyString("", "  ");
            }
        }

        static Statement expression(Expression expression) {
            return new ExpressionStatement(expression);
        }

        static Statement compound(Statement... statements) {
            return new CompoundStatement(List.of(statements));
        }

        static Statement compound(List<Statement> statements) {
            return new CompoundStatement(statements);
        }

        static Statement ifStatement(Expression condition, Statement thenStatement, @Nullable Statement elseStatement) {
            return new IfStatement(condition, thenStatement, elseStatement);
        }

        static Statement whileStatement(Expression condition, Statement body) {
            return new WhileStatement(condition, body);
        }

        static Statement forStatement(@Nullable Expression init, @Nullable Expression condition,
                                      @Nullable Expression increment, Statement body) {
            return new ForStatement(init, condition, increment, body);
        }

        static Statement returnStatement(@Nullable Expression expression) {
            return new ReturnStatement(expression);
        }

        static Statement breakStatement() {
            return new BreakStatement();
        }

        static Statement continueStatement() {
            return new ContinueStatement();
        }

        static Statement emptyStatement() {
            return new EmptyStatement();
        }

        static Statement declarationStatement(Declarator declarator, @Nullable Initializer initializer) {
            return new DeclarationStatement(declarator, initializer);
        }

        static Statement structDeclarationStatement(Declarator.StructDeclarator declarator) {
            return new StructDeclarationStatement(declarator);
        }

        static Statement functionDeclarationStatement(Declarator.FunctionDeclarator declarator,
                                                      CompoundStatement body) {
            return new FunctionDeclarationStatement(declarator, body);
        }

        static Statement define(String name, PrimaryExpression.Constant value) {
            return new Define(name, value);
        }

        static Statement include(String file) {
            return new Include(file);
        }

        static Statement typedef(Declarator declarator, PrimaryExpression.Variable name) {
            return new Typedef(declarator, name);
        }

        static Statement caseStatement(Expression expression, Statement body) {
            return new CaseStatement(expression, body);
        }

        static Statement defaultStatement(Statement body) {
            return new DefaultStatement(body);
        }

        static Statement switchStatement(Expression expression, Statement body) {
            return new SwitchStatement(expression, body);
        }

        static Statement variableDefinition(Declarator type, PrimaryExpression.Variable name) {
            return new VariableDefinition(type, name);
        }
    }
}