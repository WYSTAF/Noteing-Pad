#!/usr/bin/env python3
"""Independent Python model of the Kotlin AVL algorithm, NOT a Kotlin test runner."""
from dataclasses import dataclass
from random import Random

LEAF = 4096
@dataclass(frozen=True)
class Node:
    value: str | None
    left: 'Node | None'
    right: 'Node | None'
    length: int
    height: int

def leaf(s):
    return Node(s, None, None, len(s), 1) if s else None

def height(n):
    return n.height if n else 0

def length(n):
    return n.length if n else 0

def branch(a, b):
    assert a and b
    return Node(None, a, b, a.length + b.length, max(a.height, b.height) + 1)

def balance(a, b):
    if height(a) > height(b) + 1:
        if height(a.left) >= height(a.right):
            return branch(a.left, branch(a.right, b))
        m = a.right
        return branch(branch(a.left, m.left), branch(m.right, b))
    if height(b) > height(a) + 1:
        if height(b.right) >= height(b.left):
            return branch(branch(a, b.left), b.right)
        m = b.left
        return branch(branch(a, m.left), branch(m.right, b.right))
    return branch(a, b)

def concat(a, b):
    if not a: return b
    if not b: return a
    if a.value is not None and b.value is not None and a.length + b.length <= LEAF:
        return leaf(a.value + b.value)
    if a.height > b.height + 1:
        return balance(a.left, concat(a.right, b))
    if b.height > a.height + 1:
        return balance(concat(a, b.left), b.right)
    return branch(a, b)

def split(n, offset):
    assert 0 <= offset <= length(n)
    if offset == 0: return None, n
    if offset == n.length: return n, None
    if n.value is not None: return leaf(n.value[:offset]), leaf(n.value[offset:])
    if offset < n.left.length:
        a, b = split(n.left, offset)
        return a, concat(b, n.right)
    a, b = split(n.right, offset - n.left.length)
    return concat(n.left, a), b

def of(s):
    leaves = [leaf(s[i:i + LEAF]) for i in range(0, len(s), LEAF)]
    def build(a, b):
        if b-a == 1: return leaves[a]
        m = (a+b)//2
        return branch(build(a, m), build(m, b))
    return build(0, len(leaves)) if leaves else None

def replace(n, start, end, inserted):
    before, tail = split(n, start)
    _, after = split(tail, end-start)
    return concat(concat(before, inserted), after)

def text(n):
    if not n: return ''
    if n.value is not None: return n.value
    return text(n.left) + text(n.right)

def check(n):
    if not n: return
    if n.value is not None:
        assert n.length == len(n.value) and 0 < n.length <= LEAF
        assert n.height == 1
    else:
        assert abs(n.left.height - n.right.height) <= 1
        assert n.length == n.left.length + n.right.length
        assert n.height == max(n.left.height, n.right.height) + 1
        check(n.left); check(n.right)

def main():
    random = Random(14271)
    expected = 'start\n' * 4000
    rope = of(expected)
    for step in range(5000):
        start = random.randrange(len(expected) + 1)
        end = min(len(expected), start + random.randrange(250))
        added = ''.join(random.choice('abc  \t\n') for _ in range(random.randrange(260)))
        previous, previous_text = rope, expected
        rope = replace(rope, start, end, of(added))
        expected = expected[:start] + added + expected[end:]
        assert text(rope) == expected
        assert text(previous) == previous_text
        check(rope)
    rope = of('a' * (2 * 1024 * 1024))
    for _ in range(500):
        at = random.randrange(length(rope) + 1)
        left, right = split(rope, at)
        rope = concat(left, right)
        check(rope)
    assert text(rope) == 'a' * (2 * 1024 * 1024)
    print('PASS: 5,000 randomized edit/snapshot/balance checks; 500 large-root split/join checks.')
    print('This validates an independent algorithm model, not Kotlin compilation or Android performance.')

if __name__ == '__main__': main()
