#!/usr/bin/env python3
import argparse
import ast
import json
import operator


OPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}


def eval_node(node):
    if isinstance(node, ast.Expression):
        return eval_node(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in OPS:
        return OPS[type(node.op)](eval_node(node.left), eval_node(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in OPS:
        return OPS[type(node.op)](eval_node(node.operand))
    raise ValueError(f"unsupported expression element: {type(node).__name__}")


def calculate(expression):
    tree = ast.parse(expression, mode="eval")
    return eval_node(tree)


def main():
    parser = argparse.ArgumentParser(description="Safely calculate arithmetic expressions.")
    parser.add_argument("expressions", nargs="+", help="Arithmetic expressions")
    args = parser.parse_args()
    rows = []
    ok = True
    for expression in args.expressions:
        try:
            rows.append({"expression": expression, "ok": True, "result": calculate(expression)})
        except Exception as exc:
            ok = False
            rows.append({"expression": expression, "ok": False, "error": str(exc)})
    print(json.dumps({"ok": ok, "results": rows}, ensure_ascii=False, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
