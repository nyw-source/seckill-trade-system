# -*- coding: utf-8 -*-
"""
swagger(springfox) 注解 -> openapi3(springdoc) 注解 批量迁移
用法: python migrate_swagger.py <项目根目录>
迁移完会自动报告每个文件的替换次数与残留的 io.swagger 引用。
"""
import io
import os
import re
import sys

IMPORT_MAP = {
    "io.swagger.annotations.Api;": "io.swagger.v3.oas.annotations.tags.Tag;",
    "io.swagger.annotations.ApiOperation;": "io.swagger.v3.oas.annotations.Operation;",
    "io.swagger.annotations.ApiModel;": "io.swagger.v3.oas.annotations.media.Schema;",
    "io.swagger.annotations.ApiModelProperty;": "io.swagger.v3.oas.annotations.media.Schema;",
    "io.swagger.annotations.ApiParam;": "io.swagger.v3.oas.annotations.Parameter;",
    "io.swagger.annotations.ApiImplicitParam;": "io.swagger.v3.oas.annotations.Parameter;",
    "io.swagger.annotations.ApiImplicitParams;": "io.swagger.v3.oas.annotations.Parameters;",
}

# 逐条替换：(正则, 替换模板)
RULES = [
    # @Api(tags = "x") -> @Tag(name = "x")
    (re.compile(r'@Api\(tags\s*=\s*(".*?")\)'), r'@Tag(name = \1)'),
    # @ApiOperation(value = "x") / @ApiOperation("x") -> @Operation(summary = "x")
    (re.compile(r'@ApiOperation\(value\s*=\s*(".*?")\)'), r'@Operation(summary = \1)'),
    (re.compile(r'@ApiOperation\((".*?")\)'), r'@Operation(summary = \1)'),
    # @ApiModel(description = "x") -> @Schema(description = "x")
    (re.compile(r'@ApiModel\(description\s*=\s*(".*?")\)'), r'@Schema(description = \1)'),
    # @ApiModelProperty(value = "x", required = true) -> requiredMode
    (re.compile(r'@ApiModelProperty\(value\s*=\s*(".*?"),\s*required\s*=\s*true\)'),
     r'@Schema(description = \1, requiredMode = Schema.RequiredMode.REQUIRED)'),
    (re.compile(r'@ApiModelProperty\(value\s*=\s*(".*?"),\s*required\s*=\s*false\)'),
     r'@Schema(description = \1)'),
    # @ApiModelProperty("x") -> @Schema(description = "x")
    (re.compile(r'@ApiModelProperty\((".*?")\)'), r'@Schema(description = \1)'),
    # @ApiParam("x") -> @Parameter(description = "x")
    (re.compile(r'@ApiParam\((".*?")\)'), r'@Parameter(description = \1)'),
    # @ApiImplicitParam(name = "a", value = "b"[, paramType = "path"]) -> @Parameter(name = "a", description = "b")
    (re.compile(r'@ApiImplicitParam\(name\s*=\s*(".*?"),\s*value\s*=\s*(".*?")(?:,\s*paramType\s*=\s*".*?")?\)'),
     r'@Parameter(name = \1, description = \2)'),
    (re.compile(r'@ApiImplicitParam\(value\s*=\s*(".*?"),\s*name\s*=\s*(".*?")(?:,\s*paramType\s*=\s*".*?")?\)'),
     r'@Parameter(name = \2, description = \1)'),
    # @ApiImplicitParams({ -> @Parameters({
    (re.compile(r'@ApiImplicitParams\(\{'), r'@Parameters({'),
]


def migrate(path):
    with io.open(path, encoding="utf-8") as f:
        src = f.read()
    if "io.swagger.annotations" not in src:
        return 0
    out = src
    n = 0
    # 1) import
    for old, new in IMPORT_MAP.items():
        if old in out:
            out = out.replace(old, new)
            n += 1
    # 2) 注解
    for pat, rep in RULES:
        out, cnt = pat.subn(rep, out)
        n += cnt
    # 3) 去重 import（ApiModel + ApiModelProperty 都会变成 Schema）
    lines = out.split("\n")
    seen, kept = set(), []
    for line in lines:
        s = line.strip()
        if s.startswith("import ") and s.endswith(";"):
            if s in seen:
                continue
            seen.add(s)
        kept.append(line)
    out = "\n".join(kept)
    if out != src:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(out)
    return n


def main(root):
    total_files, total_hits, leftovers = 0, 0, []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules")]
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dirpath, fn)
            hits = migrate(p)
            if hits:
                total_files += 1
                total_hits += hits
                print("  %-90s %d 处" % (os.path.relpath(p, root), hits))
            with io.open(p, encoding="utf-8") as f:
                body = f.read()
            if "io.swagger.annotations" in body:
                leftovers.append(os.path.relpath(p, root))
    print("\n共迁移 %d 个文件 / %d 处" % (total_files, total_hits))
    if leftovers:
        print("!! 仍有 springfox 引用残留：")
        for p in leftovers:
            print("   " + p)
    else:
        print("无 springfox 残留。")


if __name__ == "__main__":
    main(sys.argv[1])
