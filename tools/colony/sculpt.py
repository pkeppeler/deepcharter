"""Posed figures from boxes: a small scene graph that flattens into block-model elements.

A Bone has a pivot (in its parent's frame), a rotation (Euler degrees, applied as Rz * Ry * Rx like a model element), boxes
(corners relative to the pivot) and child bones. flatten() walks the tree and gives each box its world rotation and pivot, which
is exactly one Minecraft element: from and to are the pivot plus the box's own corners, rotated about the pivot. So a forearm turns
with its elbow and an upper arm, as in Blockbench, but every element is written out flat.
"""
from dataclasses import dataclass, field

from models import Box, apply, euler_of, matmul, rotation_matrix

Vec = tuple[float, float, float]


@dataclass
class Part:
    """A box of a bone: lo and hi relative to the bone's pivot, a texture variable for every face (or a dict per face)."""

    lo: Vec
    hi: Vec
    texture: str | dict
    glow: bool = False
    uv_scale: float = 1.0
    piece: str = "body"


@dataclass
class Bone:
    pivot: Vec
    rotation: Vec = (0.0, 0.0, 0.0)
    parts: list = field(default_factory=list)
    children: list = field(default_factory=list)

    def box(self, lo: Vec, hi: Vec, texture: str | dict = "#bronze", glow: bool = False, uv_scale: float = 1.0,
            piece: str = "body") -> "Bone":
        if any(lo[i] >= hi[i] for i in range(3)):
            raise ValueError(f"box {lo}..{hi} is empty or inside out")
        self.parts.append(Part(lo, hi, texture, glow, uv_scale, piece))
        return self

    def centred(self, centre: Vec, size: Vec, texture: str | dict = "#bronze", glow: bool = False) -> "Bone":
        lo = tuple(centre[i] - size[i] / 2 for i in range(3))
        hi = tuple(centre[i] + size[i] / 2 for i in range(3))
        return self.box(lo, hi, texture, glow)

    def child(self, pivot: Vec, rotation: Vec = (0.0, 0.0, 0.0)) -> "Bone":
        bone = Bone(pivot, rotation)
        self.children.append(bone)
        return bone


IDENTITY = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]


def flatten(root: Bone, offset: Vec = (0.0, 0.0, 0.0), piece: str | None = None) -> list[Box]:
    """Every box of the tree (or only those of one piece) as a model element, with the root's pivot moved by offset."""
    out: list[Box] = []

    def walk(bone: Bone, parent_rot, parent_origin: Vec):
        origin = tuple(parent_origin[i] + v for i, v in enumerate(apply(parent_rot, bone.pivot)))
        rot = matmul(parent_rot, rotation_matrix(*bone.rotation))
        euler = euler_of(rot)
        for part in bone.parts:
            if piece is not None and part.piece != piece:
                continue
            lo = tuple(origin[i] + part.lo[i] for i in range(3))
            hi = tuple(origin[i] + part.hi[i] for i in range(3))
            textures = part.texture if isinstance(part.texture, dict) else {"*": part.texture}
            out.append(Box(lo, hi, textures, rotation=euler, pivot=origin, glow=part.glow, uv_scale=part.uv_scale))
        for child in bone.children:
            walk(child, rot, origin)

    walk(root, IDENTITY, offset)
    return out


def world_points(root: Bone, offset: Vec = (0.0, 0.0, 0.0), piece: str | None = None) -> list[list[Vec]]:
    """The eight world corners of every box: for silhouettes and bounds checks."""
    boxes: list[list[Vec]] = []

    def walk(bone: Bone, parent_rot, parent_origin: Vec):
        origin = tuple(parent_origin[i] + v for i, v in enumerate(apply(parent_rot, bone.pivot)))
        rot = matmul(parent_rot, rotation_matrix(*bone.rotation))
        for part in bone.parts:
            if piece is not None and part.piece != piece:
                continue
            corners = []
            for x in (part.lo[0], part.hi[0]):
                for y in (part.lo[1], part.hi[1]):
                    for z in (part.lo[2], part.hi[2]):
                        p = apply(rot, (x, y, z))
                        corners.append(tuple(origin[i] + p[i] for i in range(3)))
            boxes.append(corners)
        for child in bone.children:
            walk(child, rot, origin)

    walk(root, IDENTITY, offset)
    return boxes
