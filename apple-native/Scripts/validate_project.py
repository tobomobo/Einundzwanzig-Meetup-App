#!/usr/bin/env python3
"""Validate the checked-in Apple project using only the Python standard library.

Run from any directory: python3 apple-native/Scripts/validate_project.py
CI additionally uses --require-route-matrix. No Xcode, Swift, or network is needed.
This checks project structure and declared resources, not compilation, signing,
runtime behavior, translations, or the visual quality/decoded pixels of PNGs.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import fnmatch
import json
from pathlib import Path
import plistlib
import re
import shlex
import struct
import sys
import xml.etree.ElementTree as ET
import zlib


class Invalid(ValueError):
    """An actionable validation failure, rather than an internal exception."""


def require(condition, message):
    if not condition:
        raise Invalid(message)


def dictionary(value, label):
    require(isinstance(value, dict), f"{label}: expected a dictionary")
    return value


def sequence(value, label):
    require(isinstance(value, list), f"{label}: expected an array")
    return value


def read_text(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


@dataclass(frozen=True)
class Token:
    kind: str
    value: str
    offset: int


class Lexer:
    """Small OpenStep lexer, also used for Apple's .strings assignment syntax."""

    def __init__(self, source, label):
        self.source, self.label, self.index = source, str(label), 0

    def fail(self, message, offset=None):
        offset = self.index if offset is None else offset
        line = self.source.count("\n", 0, offset) + 1
        column = offset - self.source.rfind("\n", 0, offset)
        raise Invalid(f"{self.label}:{line}:{column}: {message}")

    def next(self):
        s = self.source
        while self.index < len(s):
            if s[self.index].isspace():
                self.index += 1
            elif s.startswith("//", self.index):
                end = s.find("\n", self.index + 2)
                self.index = len(s) if end < 0 else end
            elif s.startswith("/*", self.index):
                end = s.find("*/", self.index + 2)
                if end < 0:
                    self.fail("unterminated block comment")
                self.index = end + 2
            else:
                break
        start = self.index
        if start == len(s):
            return Token("eof", "", start)
        char = s[self.index]
        self.index += 1
        if char in "{}()=;,":
            return Token(char, char, start)
        if char == '"':
            result = []
            escapes = {"n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", "a": "\a", "v": "\v", '"': '"', "\\": "\\"}
            while self.index < len(s):
                char = s[self.index]
                self.index += 1
                if char == '"':
                    return Token("quoted", "".join(result), start)
                if char != "\\":
                    result.append(char)
                    continue
                if self.index == len(s):
                    self.fail("unterminated escape", start)
                char = s[self.index]
                self.index += 1
                if char in "uU":
                    digits = s[self.index:self.index + 4]
                    if len(digits) != 4 or any(c not in "0123456789abcdefABCDEF" for c in digits):
                        self.fail("expected four hex digits after Unicode escape")
                    result.append(chr(int(digits, 16)))
                    self.index += 4
                elif char in "01234567":
                    digits = char
                    while len(digits) < 3 and self.index < len(s) and s[self.index] in "01234567":
                        digits += s[self.index]
                        self.index += 1
                    result.append(chr(int(digits, 8)))
                elif char == "\n":
                    pass
                elif char == "\r":
                    if self.index < len(s) and s[self.index] == "\n":
                        self.index += 1
                else:
                    result.append(escapes.get(char, char))
            self.fail("unterminated quoted string", start)
        while self.index < len(s) and not s[self.index].isspace() and s[self.index] not in '{}()=;,"':
            if s.startswith(("/*", "//"), self.index):
                break
            self.index += 1
        return Token("atom", s[start:self.index], start)


class OpenStepParser:
    def __init__(self, source, label):
        self.lexer = Lexer(source, label)
        self.token = self.lexer.next()

    def take(self, kind=None):
        token = self.token
        if kind is not None and token.kind != kind:
            self.lexer.fail(f"expected {kind!r}, got {token.value!r}", token.offset)
        self.token = self.lexer.next()
        return token

    def scalar(self, quoted=False):
        if self.token.kind not in (("quoted",) if quoted else ("atom", "quoted")):
            self.lexer.fail("expected a string", self.token.offset)
        return self.take().value

    def value(self):
        if self.token.kind == "{":
            self.take("{")
            result = {}
            while self.token.kind != "}":
                key = self.scalar()
                require(key not in result, f"{self.lexer.label}: duplicate dictionary key {key!r}")
                self.take("=")
                result[key] = self.value()
                self.take(";")
            self.take("}")
            return result
        if self.token.kind == "(":
            self.take("(")
            result = []
            while self.token.kind != ")":
                result.append(self.value())
                if self.token.kind != ")":
                    self.take(",")
            self.take(")")
            return result
        return self.scalar()

    def parse(self):
        value = self.value()
        self.take("eof")
        return value

    def strings(self):
        result = {}
        while self.token.kind != "eof":
            key = self.scalar(quoted=True)
            # Apple Unicode escapes can encode UTF-16 surrogate pairs.
            key = key.encode("utf-16-le", "surrogatepass").decode("utf-16-le")
            require(key not in result, f"{self.lexer.label}: duplicate localization key {key!r}")
            self.take("=")
            result[key] = self.scalar(quoted=True)
            self.take(";")
        return result


PHASES = ("PBXSourcesBuildPhase", "PBXFrameworksBuildPhase", "PBXResourcesBuildPhase")
GROUPS = ("PBXGroup", "PBXVariantGroup", "PBXFileSystemSynchronizedRootGroup")
FILES = ("PBXFileReference", *GROUPS)
# Only schema-defined relationships are edges. UUID-shaped setting values and
# TargetAttributes metadata are never guessed to be references by a regex.
REFERENCES = {
    "PBXProject": {"mainGroup": (False, ("PBXGroup",)), "productRefGroup": (False, ("PBXGroup",)), "buildConfigurationList": (False, ("XCConfigurationList",)), "targets": (True, ("PBXNativeTarget",)), "packageReferences": (True, ("XCLocalSwiftPackageReference",))},
    "PBXNativeTarget": {"buildConfigurationList": (False, ("XCConfigurationList",)), "buildPhases": (True, PHASES), "productReference": (False, ("PBXFileReference",)), "fileSystemSynchronizedGroups": (True, ("PBXFileSystemSynchronizedRootGroup",)), "packageProductDependencies": (True, ("XCSwiftPackageProductDependency",)), "dependencies": (True, ("PBXTargetDependency",)), "buildRules": (True, ("PBXBuildRule",))},
    "PBXGroup": {"children": (True, FILES)},
    "PBXVariantGroup": {"children": (True, ("PBXFileReference",))},
    "PBXFileSystemSynchronizedRootGroup": {"exceptions": (True, ("PBXFileSystemSynchronizedBuildFileExceptionSet", "PBXFileSystemSynchronizedGroupBuildPhaseMembershipExceptionSet"))},
    "PBXFileSystemSynchronizedBuildFileExceptionSet": {"target": (False, ("PBXNativeTarget",))},
    "PBXFileSystemSynchronizedGroupBuildPhaseMembershipExceptionSet": {"buildPhase": (False, PHASES)},
    "PBXBuildFile": {"fileRef": (False, ("PBXFileReference", "PBXVariantGroup")), "productRef": (False, ("XCSwiftPackageProductDependency",))},
    "XCBuildConfiguration": {"baseConfigurationReference": (False, ("PBXFileReference",))},
    "XCConfigurationList": {"buildConfigurations": (True, ("XCBuildConfiguration",))},
    "XCSwiftPackageProductDependency": {"package": (False, ("XCLocalSwiftPackageReference",))},
    "XCLocalSwiftPackageReference": {}, "PBXFileReference": {},
    "PBXTargetDependency": {"target": (False, ("PBXNativeTarget",))},
    "PBXBuildRule": {},
    **{phase: {"files": (True, ("PBXBuildFile",))} for phase in PHASES},
}


class Project:
    def __init__(self, apple):
        self.apple = apple
        self.path = apple / "Einundzwanzig.xcodeproj/project.pbxproj"
        self.data = dictionary(OpenStepParser(read_text(self.path), self.path).parse(), "project")
        self.objects = dictionary(self.data.get("objects"), "objects")
        self.root_id = self.data.get("rootObject")
        self.parents = {}
        self.paths = {}

    def obj(self, oid, expected=()):
        require(isinstance(oid, str) and oid in self.objects, f"dangling object reference {oid!r}")
        obj = dictionary(self.objects[oid], oid)
        require(not expected or obj.get("isa") in expected, f"{oid}: expected {expected}, got {obj.get('isa')!r}")
        return obj

    def of_type(self, isa):
        return [(oid, obj) for oid, obj in self.objects.items() if isinstance(obj, dict) and obj.get("isa") == isa]

    def only(self, isa):
        items = self.of_type(isa)
        require(len(items) == 1, f"expected exactly one {isa}, found {len(items)}")
        return items[0]

    def validate_graph(self):
        self.parents.clear()
        self.paths.clear()
        require(self.data.get("objectVersion") == "77", "project objectVersion must be 77")
        self.root = self.obj(self.root_id, ("PBXProject",))
        edges = defaultdict(list)
        for oid, raw in self.objects.items():
            require(re.fullmatch(r"[A-Fa-f0-9]{24}", oid) is not None, f"invalid project object ID {oid!r}")
            obj = dictionary(raw, oid)
            isa = obj.get("isa")
            require(isa in REFERENCES, f"{oid}: unsupported object type {isa!r}; remote packages/extra target types are not allowed")
            for field, (many, expected) in REFERENCES[isa].items():
                if field not in obj:
                    continue
                values = sequence(obj[field], f"{oid}.{field}") if many else [obj[field]]
                require(all(isinstance(v, str) for v in values), f"{oid}.{field}: references must be object IDs")
                require(len(values) == len(set(values)), f"{oid}.{field}: duplicate object references")
                for value in values:
                    self.obj(value, expected)
                    edges[oid].append(value)
                    if field == "children":
                        require(value not in self.parents, f"{value}: belongs to multiple groups")
                        self.parents[value] = oid
        reached, pending = set(), [self.root_id]
        while pending:
            oid = pending.pop()
            if oid not in reached:
                reached.add(oid)
                pending.extend(edges[oid])
        require(reached == set(self.objects), f"orphan project objects: {', '.join(sorted(set(self.objects) - reached))}")
        require(self.root.get("mainGroup") not in self.parents, "mainGroup may not be a child of another group (group cycle)")
        self.target_id, self.target = self.only("PBXNativeTarget")
        require(self.root.get("targets") == [self.target_id], "project.targets must contain only the app target")
        attrs = dictionary(self.root.get("attributes", {}), "project.attributes")
        target_attrs = dictionary(attrs.get("TargetAttributes", {}), "TargetAttributes")
        require(set(target_attrs) <= {self.target_id}, "TargetAttributes contains a stale target ID")
        require(self.target.get("name") == "Einundzwanzig" and self.target.get("productName") == "Einundzwanzig", "app target name/productName must be Einundzwanzig")
        require(self.target.get("productType") == "com.apple.product-type.application", "target must be an application")
        self.product_id = self.target.get("productReference")
        product = self.obj(self.product_id, ("PBXFileReference",))
        require(product.get("path") == "Einundzwanzig.app" and product.get("sourceTree") == "BUILT_PRODUCTS_DIR" and product.get("explicitFileType") == "wrapper.application", "product must be Einundzwanzig.app in BUILT_PRODUCTS_DIR")
        products = self.obj(self.root.get("productRefGroup"), ("PBXGroup",))
        require(products.get("children") == [self.product_id], "Products group must contain only Einundzwanzig.app")
        require(not self.target.get("dependencies") and not self.target.get("buildRules"), "unexpected target dependencies or custom build rules")
        require(not self.root.get("projectReferences"), "external project references are not allowed")

    def resolve(self, oid, trail=()):
        if oid in self.paths:
            return self.paths[oid]
        require(oid not in trail, f"group path cycle at {oid}")
        obj = self.obj(oid)
        tree = obj.get("sourceTree", "<group>")
        if tree == "BUILT_PRODUCTS_DIR":
            require(oid == self.product_id, f"{oid}: unexpected generated product reference")
            return None
        require(tree in ("<group>", "SOURCE_ROOT"), f"{oid}: unsupported sourceTree {tree!r}; cannot resolve without Xcode")
        if tree == "SOURCE_ROOT" or oid == self.root.get("mainGroup"):
            base = self.apple
        else:
            require(oid in self.parents, f"{oid}: file/group is absent from the navigator")
            base = self.resolve(self.parents[oid], (*trail, oid))
            require(base is not None, f"{oid}: cannot nest under a generated product")
        # A variant is a virtual localization container, not a disk directory.
        path = base if obj.get("isa") == "PBXVariantGroup" else base / obj.get("path", "")
        self.paths[oid] = path.resolve()
        return self.paths[oid]

    def validate_paths(self):
        require(self.root.get("projectDirPath", "") == "" and self.root.get("projectRoot", "") == "", "project must use apple-native as its source root")
        for oid, obj in self.objects.items():
            if obj.get("isa") in FILES:
                path = self.resolve(oid)
                if path is not None:
                    require(path.exists(), f"{oid}: missing referenced path {path}")
                    if obj["isa"] in ("PBXGroup", "PBXFileSystemSynchronizedRootGroup"):
                        require(path.is_dir(), f"{oid}: group path is not a directory: {path}")
        sync_id, sync = self.only("PBXFileSystemSynchronizedRootGroup")
        require(sync.get("path") == "App" and self.resolve(sync_id) == self.apple / "App", "synchronized root group must resolve to App")
        require(self.target.get("fileSystemSynchronizedGroups") == [sync_id], "app target must own the App synchronized root group")
        exceptions = sequence(sync.get("exceptions", []), "App.exceptions")
        if (self.apple / "App/Platform/Tests").is_dir():
            require(len(exceptions) == 1, "App must exclude only Platform/Tests using one synchronized exception set")
        else:
            require(not exceptions, "unexpected App synchronized exceptions without Platform/Tests")
        for exception_id in exceptions:
            exception = self.obj(exception_id, ("PBXFileSystemSynchronizedBuildFileExceptionSet",))
            require(exception.get("target") == self.target_id, "synchronized test exception must belong to the app target")
            require(exception.get("membershipExceptions") == ["Platform/Tests"], "only Platform/Tests may be excluded from synchronized App membership; production Swift files must remain included")
            require(not exception.get("platformFilters") and not exception.get("publicHeaders") and not exception.get("privateHeaders") and not exception.get("additionalCompilerFlagsByRelativePath") and not exception.get("attributesByRelativePath"), "test exception set must not alter production file settings")
        require((self.apple / "App/EinundzwanzigApp.swift").is_file(), "missing App/EinundzwanzigApp.swift entry point")
        for oid, obj in self.of_type("PBXFileReference"):
            path = self.resolve(oid)
            require(path is None or not path.is_relative_to(self.apple / "App"), f"{oid}: explicit App file duplicates synchronized membership")

    def validate_membership(self):
        phase_ids = sequence(self.target.get("buildPhases"), "target.buildPhases")
        phase_map = {self.obj(oid)["isa"]: self.obj(oid) for oid in phase_ids}
        require(len(phase_ids) == 3 and set(phase_map) == set(PHASES), "app must have exactly one Sources, Frameworks and Resources build phase")
        all_build_files = []
        members = {}
        for kind, phase in phase_map.items():
            require(phase.get("buildActionMask") == "2147483647" and phase.get("runOnlyForDeploymentPostprocessing") == "0", f"{kind}: phase must run for normal builds")
            files = sequence(phase.get("files"), f"{kind}.files")
            all_build_files.extend(files)
            refs = []
            for oid in files:
                build_file = self.obj(oid, ("PBXBuildFile",))
                require(("fileRef" in build_file) != ("productRef" in build_file), f"{oid}: build file needs exactly one fileRef or productRef")
                require(not build_file.get("platformFilter") and not build_file.get("platformFilters"), f"{oid}: shared resource/package may not be platform-filtered")
                refs.append(build_file.get("fileRef", build_file.get("productRef")))
            require(len(refs) == len(set(refs)), f"{kind}: duplicate build membership")
            members[kind] = refs
        require(len(all_build_files) == len(set(all_build_files)), "a build file belongs to multiple phases")
        require(set(all_build_files) == {oid for oid, _ in self.of_type("PBXBuildFile")}, "unattached PBXBuildFile objects")
        require(not members["PBXSourcesBuildPhase"], "Sources must use synchronized App membership, without explicit duplicate sources")
        variant_id, variant = self.only("PBXVariantGroup")
        require(variant.get("name", variant.get("path")) == "Localizable.strings", "variant group must be Localizable.strings")
        parent = self.obj(self.parents.get(variant_id), ("PBXGroup",))
        require(parent.get("path") == "Resources" and self.resolve(self.parents[variant_id]) == self.apple / "Resources", "Localizable.strings must belong to the Resources group")
        children = sequence(variant.get("children"), "Localizable.strings.children")
        locales = {}
        for oid in children:
            child = self.obj(oid, ("PBXFileReference",))
            locale = child.get("name")
            require(locale in ("de", "en", "es") and locale not in locales, f"{oid}: invalid or duplicate localization {locale!r}")
            require(child.get("path") == f"{locale}.lproj/Localizable.strings", f"{oid}: localization child path must be relative to Resources")
            require(self.resolve(oid) == self.apple / f"Resources/{locale}.lproj/Localizable.strings", f"{oid}: wrong localization location")
            require(child.get("lastKnownFileType") == "text.plist.strings", f"{oid}: wrong localization file type")
            locales[locale] = self.resolve(oid)
        require(set(locales) == {"de", "en", "es"}, "localization variant must include de, en and es exactly once")
        self.locales = locales
        regions = sequence(self.root.get("knownRegions"), "knownRegions")
        require(set(locales) <= set(regions), "knownRegions must include de, en and es")
        require(self.root.get("developmentRegion") in locales, "developmentRegion must be a supported locale")
        catalogs = [(oid, obj) for oid, obj in self.of_type("PBXFileReference") if self.resolve(oid) == self.apple / "Resources/Assets.xcassets"]
        require(len(catalogs) == 1, "expected one Resources/Assets.xcassets file reference")
        catalog_id, catalog = catalogs[0]
        require(catalog.get("lastKnownFileType") == "folder.assetcatalog", "Assets.xcassets must use folder.assetcatalog type")
        require(self.parents.get(catalog_id) == self.parents[variant_id], "asset catalog must belong to Resources group")
        require(set(members["PBXResourcesBuildPhase"]) == {catalog_id, variant_id}, "Resources phase must contain exactly Assets.xcassets and Localizable.strings variant")
        package_id, package = self.only("XCLocalSwiftPackageReference")
        require(self.root.get("packageReferences") == [package_id] and package.get("relativePath") == "Packages/MeetupCore", "project must reference only the local Packages/MeetupCore package")
        require((self.apple / "Packages/MeetupCore/Package.swift").is_file(), "local MeetupCore Package.swift does not exist")
        dependencies = dict(self.of_type("XCSwiftPackageProductDependency"))
        require({item.get("productName") for item in dependencies.values()} == {"MeetupCore", "MeetupNostr"} and len(dependencies) == 2, "expected only MeetupCore and MeetupNostr products")
        require(all(item.get("package") == package_id for item in dependencies.values()), "both products must reference the local package")
        require(set(self.target.get("packageProductDependencies", [])) == set(dependencies), "target must depend on both local products")
        require(set(members["PBXFrameworksBuildPhase"]) == set(dependencies), "Frameworks phase must link both local products")
        expected = {"identity": "swift-secp256k1", "kind": "remoteSourceControl", "location": "https://github.com/21-DOT-DEV/swift-secp256k1", "state": {"revision": "e70a10e036a55fffea31568f0af92d69b6d449cd", "version": "0.23.2"}}
        for path in (self.apple / "Packages/MeetupCore/Package.resolved", self.apple / "Einundzwanzig.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"):
            require(load_json(path).get("pins") == [expected], f"{path}: expected exact reviewed source-provider pin only")


def strip_config_comments(text):
    """Remove comments outside quotes while preserving lines and quoted URLs."""
    output, i, quote, block = [], 0, False, False
    while i < len(text):
        if block:
            if text.startswith("*/", i):
                block = False
                output.append(" ")
                i += 2
            else:
                if text[i] == "\n":
                    output.append("\n")
                i += 1
        elif not quote and text.startswith("/*", i):
            block = True
            i += 2
        elif not quote and text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        else:
            char = text[i]
            output.append(char)
            i += 1
            if char == "\\" and i < len(text):
                output.append(text[i])
                i += 1
            elif char == '"':
                quote = not quote
    require(not block and not quote, "unterminated xcconfig comment or quoted value")
    return "".join(output)


def setting_value(value):
    if isinstance(value, list):
        require(all(isinstance(x, str) for x in value), "build setting array must contain strings")
        return " ".join(value)
    require(isinstance(value, str), "build setting values must be strings or arrays")
    return value


def merge_settings(lower, upper):
    result = dict(lower)
    for key, raw in upper.items():
        value = setting_value(raw)
        result[key] = value.replace("$(inherited)", result.get(key, "")).replace("${inherited}", result.get(key, ""))
    return result


def load_xcconfig(path, trail=()):
    path = path.resolve()
    require(path not in trail, f"xcconfig include cycle: {path}")
    settings, included = {}, set()
    text = strip_config_comments(read_text(path)).replace("\\\n", "")
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        include = re.fullmatch(r'#include(\?)?\s+"([^"\n]+)"', line)
        if include:
            child = (path.parent / include[2]).resolve()
            if include[1] and not child.exists():
                continue
            values, descendants = load_xcconfig(child, (*trail, path))
            settings = merge_settings(settings, values)
            included |= {child} | descendants
            continue
        match = re.fullmatch(r"([A-Za-z_][A-Za-z_0-9]*(?:\[[^\]\n]+\])*)\s*(\+=|\?=|=)\s*(.*)", line)
        require(match is not None, f"{path}:{number}: malformed xcconfig assignment/include: {line}")
        key, operator, value = match.groups()
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        if operator == "?=" and key in settings:
            continue
        if operator == "+=":
            value = settings.get(key, "") + " " + value
        settings = merge_settings(settings, {key: value})
    return settings, included


def sdk_settings(settings, sdk, configuration):
    result = {key: value for key, value in settings.items() if "[" not in key}
    for key, value in settings.items():
        if "[" not in key:
            continue
        base = key.split("[", 1)[0]
        conditions = re.findall(r"\[([^=\]]+)=([^\]]+)\]", key)
        require(conditions and base + "".join(f"[{name}={pattern}]" for name, pattern in conditions) == key, f"malformed conditional setting {key}")
        matches = True
        for name, pattern in conditions:
            require(name in ("sdk", "config"), f"unsupported xcconfig condition {name!r}; validator needs explicit support")
            matches &= fnmatch.fnmatchcase(sdk if name == "sdk" else configuration, pattern)
        if matches:
            result[base] = value
    return result


def expand(value, settings):
    for _ in range(20):
        next_value = re.sub(r"\$\(([^)]+)\)|\$\{([^}]+)\}", lambda m: settings.get(m[1] or m[2], m[0]), value)
        if next_value == value:
            return value
        value = next_value
    raise Invalid("cyclic build setting expansion")


def configurations(project, owner, label):
    config_list = project.obj(owner.get("buildConfigurationList"), ("XCConfigurationList",))
    require(config_list.get("defaultConfigurationName") == "Release", f"{label}: default configuration must be Release")
    configs = {}
    ids = sequence(config_list.get("buildConfigurations"), f"{label}.buildConfigurations")
    for oid in ids:
        config = project.obj(oid, ("XCBuildConfiguration",))
        name = config.get("name")
        require(name in ("Debug", "Release") and name not in configs, f"{label}: invalid/duplicate configuration {name!r}")
        settings = {}
        if "baseConfigurationReference" in config:
            path = project.resolve(config["baseConfigurationReference"])
            require(path == project.apple / f"Configuration/{name}.xcconfig", f"{label}/{name}: incorrect base configuration path {path}")
            settings, included = load_xcconfig(path)
            require(project.apple / "Configuration/Base.xcconfig" in included, f"{path}: must include Base.xcconfig")
        elif label == "target":
            raise Invalid(f"target/{name}: missing baseConfigurationReference")
        configs[name] = merge_settings(settings, dictionary(config.get("buildSettings"), f"{label}/{name}.buildSettings"))
    require(set(configs) == {"Debug", "Release"}, f"{label}: must define Debug and Release")
    return configs


def validate_configurations(project):
    common = project.apple / "Configuration/Base.xcconfig"
    require(common.is_file(), f"missing {common}")
    project_configs = configurations(project, project.root, "project")
    target_configs = configurations(project, project.target, "target")
    project.effective = {}
    required = {"PRODUCT_NAME": "Einundzwanzig", "PRODUCT_BUNDLE_IDENTIFIER": "space.einundzwanzig.meetup.apple", "MARKETING_VERSION": "0.1.0", "CURRENT_PROJECT_VERSION": "1", "SDKROOT": "auto", "SUPPORTS_MACCATALYST": "NO", "GENERATE_INFOPLIST_FILE": "NO", "INFOPLIST_FILE": "Configuration/Info.plist", "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon"}
    for name in ("Debug", "Release"):
        settings = merge_settings(project_configs[name], target_configs[name])
        for sdk in ("iphoneos", "iphonesimulator", "macosx"):
            effective = sdk_settings(settings, sdk, name)
            effective.update(SRCROOT=str(project.apple), PROJECT_DIR=str(project.apple), TARGET_NAME="Einundzwanzig", EXECUTABLE_NAME="Einundzwanzig")
            effective = {key: expand(value, effective) for key, value in effective.items()}
            prefix = f"{name}/{sdk}"
            for key, expected in required.items():
                require(effective.get(key) == expected, f"{prefix}: {key} must be {expected!r}, got {effective.get(key)!r}")
            require(effective.get("SWIFT_VERSION") in ("6", "6.0"), f"{prefix}: SWIFT_VERSION must be 6.0")
            for key in ("IPHONEOS_DEPLOYMENT_TARGET", "MACOSX_DEPLOYMENT_TARGET"):
                require(effective.get(key) in ("26", "26.0"), f"{prefix}: {key} must be 26.0")
            platforms = shlex.split(effective.get("SUPPORTED_PLATFORMS", ""))
            require(len(platforms) == 3 and set(platforms) == {"iphoneos", "iphonesimulator", "macosx"}, f"{prefix}: wrong SUPPORTED_PLATFORMS")
            entitlement = effective.get("CODE_SIGN_ENTITLEMENTS", "")
            require(entitlement == ("Configuration/Einundzwanzig-macOS.entitlements" if sdk == "macosx" else ""), f"{prefix}: incorrect platform-conditional entitlements {entitlement!r}")
            if "INFOPLIST_KEY_UILaunchScreen_Generation" in effective:
                require(effective["INFOPLIST_KEY_UILaunchScreen_Generation"] == "YES", f"{prefix}: launch-screen generation setting must be YES when supplied")
            if name == "Debug":
                require(effective.get("SWIFT_OPTIMIZATION_LEVEL") == "-Onone", f"{prefix}: Debug must use -Onone")
            else:
                require(effective.get("SWIFT_OPTIMIZATION_LEVEL") in ("-O", "-Osize"), f"{prefix}: Release must use optimized Swift")
            project.effective[name, sdk] = effective
        require("CODE_SIGN_ENTITLEMENTS[sdk=macosx*]" in settings, f"{name}: expected conditional macOS entitlements setting")
    require(len(project.of_type("XCBuildConfiguration")) == 4, "expected exactly four project/target build configurations")


def load_plist(path):
    with path.open("rb") as file:
        return dictionary(plistlib.load(file), str(path))


def validate_plists(project):
    info = load_plist(project.apple / "Configuration/Info.plist")
    ats = dictionary(info.get("NSAppTransportSecurity", {}), "Info.plist NSAppTransportSecurity")
    for key in ("NSAllowsArbitraryLoads", "NSAllowsArbitraryLoadsForMedia", "NSAllowsArbitraryLoadsInWebContent"):
        require(ats.get(key, False) is False, f"Info.plist must not enable ATS arbitrary loads: {key}")
    expected = {"CFBundleIdentifier": "space.einundzwanzig.meetup.apple", "CFBundleName": "Einundzwanzig", "CFBundleExecutable": "Einundzwanzig", "CFBundlePackageType": "APPL", "CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "1"}
    for (name, sdk), settings in project.effective.items():
        for key, value in expected.items():
            require(isinstance(info.get(key), str) and expand(info[key], settings) == value, f"Info.plist {key} does not resolve to {value!r} for {name}/{sdk}")
    require(isinstance(info.get("NSCameraUsageDescription"), str) and info["NSCameraUsageDescription"].strip(), "Info.plist needs a camera usage description for the iOS scanner")
    calendar_key = "NSCalendarsWriteOnlyAccessUsageDescription"
    require(isinstance(info.get(calendar_key), str) and info[calendar_key].strip(), "Info.plist needs a calendar write-only access usage description")
    launch_setting = all(project.effective[name, sdk].get("INFOPLIST_KEY_UILaunchScreen_Generation") == "YES" for name in ("Debug", "Release") for sdk in ("iphoneos", "iphonesimulator"))
    require(isinstance(info.get("UILaunchScreen"), dict) or launch_setting, "manual Info.plist must define UILaunchScreen, or enable iPhone launch-screen generation")
    entitlements = load_plist(project.apple / "Configuration/Einundzwanzig-macOS.entitlements")
    for key in ("com.apple.security.app-sandbox", "com.apple.security.network.client", "com.apple.security.personal-information.calendars", "com.apple.security.files.user-selected.read-write", "com.apple.security.personal-information.location"):
        require(entitlements.get(key) is True, f"macOS entitlements must enable {key}")
    require(not any("icloud" in key.lower() or "ubiquity" in key.lower() for key in entitlements), "iCloud/ubiquity entitlements are outside the local-only scaffold")
    require(bool(info.get("NSLocationWhenInUseUsageDescription")) and bool(info.get("NSLocationUsageDescription")), "iOS/macOS foreground location descriptions required")


def validate_scheme(project):
    directory = project.apple / "Einundzwanzig.xcodeproj/xcshareddata/xcschemes"
    schemes = sorted(directory.glob("*.xcscheme"))
    path = directory / "Einundzwanzig.xcscheme"
    require(schemes == [path], "expected exactly one shared scheme: Einundzwanzig.xcscheme")
    root = ET.parse(path).getroot()
    require(root.tag == "Scheme", f"{path}: expected Scheme XML root")
    references = root.findall(".//BuildableReference")
    require(bool(references), "scheme has no buildable references")
    for reference in references:
        expected = {"BlueprintIdentifier": project.target_id, "BlueprintName": "Einundzwanzig", "BuildableName": "Einundzwanzig.app", "ReferencedContainer": "container:Einundzwanzig.xcodeproj", "BuildableIdentifier": "primary"}
        for key, value in expected.items():
            require(reference.get(key) == value, f"scheme {key}: expected {value!r}, got {reference.get(key)!r}")
        project.obj(reference.get("BlueprintIdentifier"), ("PBXNativeTarget",))
    entries = root.findall("./BuildAction/BuildActionEntries/BuildActionEntry")
    require(len(entries) == 1 and entries[0].find("BuildableReference") is not None, "scheme must build exactly the app target")
    for flag in ("buildForTesting", "buildForRunning", "buildForProfiling", "buildForArchiving", "buildForAnalyzing"):
        require(entries[0].get(flag) == "YES", f"scheme build entry must enable {flag}")
    for action, config in (("LaunchAction", "Debug"), ("TestAction", "Debug"), ("AnalyzeAction", "Debug"), ("ProfileAction", "Release"), ("ArchiveAction", "Release")):
        actions = root.findall(action)
        require(len(actions) == 1 and actions[0].get("buildConfiguration") == config, f"scheme {action} must use {config}")
        if action in ("LaunchAction", "ProfileAction"):
            require(actions[0].find("./BuildableProductRunnable/BuildableReference") is not None, f"scheme {action} must run the app product")


def validate_localizations(project):
    translations = {}
    for locale, path in sorted(project.locales.items()):
        entries = OpenStepParser(read_text(path), path).strings()
        require(bool(entries), f"{path}: localization must not be empty")
        translations[locale] = entries
    baseline = set(translations["de"])
    formats = re.compile(r"%(?:[0-9]+\$)?[-+#0 ]*(?:[0-9]+|\*)?(?:\.(?:[0-9]+|\*))?(?:hh|ll|[hlLjztq])?[@diuoxXfFeEgGaAcCsSp%]")
    for locale, entries in translations.items():
        keys = set(entries)
        require(keys == baseline, f"{locale} localization differs from de; missing={sorted(baseline - keys)}, extra={sorted(keys - baseline)}")
        for key, value in entries.items():
            require(Counter(formats.findall(value)) == Counter(formats.findall(translations["de"][key])), f"{locale}: placeholder mismatch for {key!r}")
    # Literal UI keys plus the two interpolated keys. Dynamic enum/glossary keys
    # are reviewed alongside their source lists; SF Symbols are not UI keys.
    literal = re.compile(r'(?:\b(?:Text|Button|Label|NavigationLink|LabeledContent|Picker|Toggle|TextField|ProgressView|DatePicker|ContentUnavailableView|CommandMenu)|\.(?:navigationTitle|accessibilityLabel|alert|confirmationDialog))\(\s*"([A-Za-z][A-Za-z0-9_]*\.[A-Za-z0-9_.]+)"')
    required = {"home.greeting %@", "badge.previewCount %lld"}
    for path in (project.apple / "App").rglob("*.swift"):
        if "Tests" not in path.parts:
            required.update(literal.findall(read_text(path)))
    require(required <= baseline, f"missing literal SwiftUI localization keys: {sorted(required - baseline)}")
    return f"{len(baseline)} keys in each of de/en/es; placeholder parity and literal UI keys pass"


def load_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"{path}: duplicate JSON key {key!r}")
            result[key] = value
        return result
    return dictionary(json.loads(read_text(path), object_pairs_hook=pairs), str(path))


def png_dimensions(path):
    with path.open("rb") as file:
        header = file.read(33)
    require(len(header) == 33 and header[:8] == b"\x89PNG\r\n\x1a\n" and header[8:16] == b"\x00\x00\x00\rIHDR", f"{path}: invalid PNG signature/IHDR")
    require(zlib.crc32(header[12:29]) & 0xFFFFFFFF == struct.unpack(">I", header[29:33])[0], f"{path}: corrupt PNG IHDR checksum")
    width, height = struct.unpack(">II", header[16:24])
    require(width > 0 and height > 0, f"{path}: invalid PNG dimensions")
    return width, height


def validate_assets(apple):
    catalog = apple / "Resources/Assets.xcassets"
    root = load_json(catalog / "Contents.json")
    require(dictionary(root.get("info"), "asset catalog info").get("version") == 1, "asset catalog info.version must be 1")
    for contents in sorted(catalog.rglob("Contents.json")):
        data = load_json(contents)
        for kind in ("images", "data"):
            for entry in sequence(data.get(kind, []), f"{contents}:{kind}"):
                entry = dictionary(entry, f"{contents}:{kind} entry")
                filename = entry.get("filename")
                if filename is not None:
                    require(isinstance(filename, str) and filename and Path(filename).name == filename, f"{contents}: invalid asset filename {filename!r}")
                    require((contents.parent / filename).is_file(), f"{contents}: missing asset {filename}")
    icon = catalog / "AppIcon.appiconset"
    images = sequence(load_json(icon / "Contents.json").get("images"), "AppIcon.images")
    expected = {("mac", f"{size}x{size}", f"{scale}x", ""): size * scale for size in (16, 32, 128, 256, 512) for scale in (1, 2)}
    expected["universal", "1024x1024", "1x", "ios"] = 1024
    seen, filenames = set(), set()
    for entry in images:
        entry = dictionary(entry, "AppIcon entry")
        slot = (entry.get("idiom"), entry.get("size"), entry.get("scale", "1x"), entry.get("platform", ""))
        require(slot in expected, f"AppIcon has unexpected slot {slot}")
        require(slot not in seen, f"AppIcon duplicates slot {slot}")
        require(not entry.get("appearances"), "AppIcon alternate appearances are not declared by this scaffold")
        seen.add(slot)
        filename = entry.get("filename")
        require(isinstance(filename, str) and filename and Path(filename).name == filename and filename.lower().endswith(".png"), f"AppIcon {slot}: missing/invalid PNG filename")
        filenames.add(filename)
        dimensions = png_dimensions(icon / filename)
        pixels = expected[slot]
        require(dimensions == (pixels, pixels), f"AppIcon {filename} ({slot}) is {dimensions}, expected {(pixels, pixels)}")
    require(seen == set(expected), f"AppIcon missing slots: {sorted(set(expected) - seen)}")
    actual_pngs = {p.name for p in icon.iterdir() if p.suffix.lower() == ".png"}
    require(actual_pngs == filenames, f"AppIcon has unreferenced PNGs: {sorted(actual_pngs - filenames)}")
    return "iOS universal 1024px and all 10 macOS icon slots"


def markdown_cells(line):
    # Pipes inside backticks (or escaped pipes) do not split Markdown cells.
    cells, current, quoted, escaped = [], [], False, False
    for char in line.strip().strip("|"):
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "`":
            quoted = not quoted
            current.append(char)
        elif char == "|" and not quoted:
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    cells.append("".join(current).strip())
    return cells


def markdown_tables(text):
    lines = text.splitlines()
    for i in range(len(lines) - 1):
        if "|" not in lines[i]:
            continue
        headers = markdown_cells(lines[i])
        separators = markdown_cells(lines[i + 1])
        if len(headers) != len(separators) or not all(re.fullmatch(r":?-{3,}:?", x.replace(" ", "")) for x in separators):
            continue
        rows = []
        for line in lines[i + 2:]:
            if not line.strip() or "|" not in line:
                break
            row = markdown_cells(line)
            require(len(row) == len(headers), f"Markdown table row has {len(row)} cells, expected {len(headers)}: {line}")
            rows.append(row)
        yield headers, rows


def code_cell(cell, label):
    match = re.fullmatch(r"`([^`]+)`", cell.strip())
    require(match is not None, f"{label}: expected exactly one backticked route ID, got {cell!r}")
    return match[1]


def canonical_routes(repo):
    path = repo / "android-native/core/navigation/ROUTES.md"
    text = read_text(path)
    section = re.search(r"^##\s+Destinations\s*\n(.*?)(?=^##\s|\Z)", text, flags=re.M | re.S)
    require(section is not None, f"{path}: missing ## Destinations section")
    tables = list(markdown_tables(section[1]))
    require(bool(tables), f"{path}: missing Destinations table")
    headers, rows = tables[0]
    normalized = [re.sub(r"[^a-z]", "", h.lower()) for h in headers]
    require("navkey" in normalized, f"{path}: first Destinations table needs NavKey column")
    index = normalized.index("navkey")
    # The canonical table contains bold domain headings, with all other cells
    # empty; these are group labels, not destinations.
    rows = [row for row in rows if not (row[index].startswith("**") and all(not cell for i, cell in enumerate(row) if i != index))]
    routes = [code_cell(row[index], str(path)) for row in rows]
    require(len(routes) == 57 and len(set(routes)) == 57, f"{path}: expected 57 unique canonical NavKeys, found {len(routes)} rows/{len(set(routes))} unique")
    return set(routes)


def validate_route_matrix(apple, required):
    repo = apple.parent
    dart_files = sorted((repo / "lib/screens").rglob("*.dart"))
    suffix_count = sum(path.name.endswith("_screen.dart") for path in dart_files)
    inventory = f"Flutter inventory: {len(dart_files)} Dart files ({suffix_count} *_screen.dart); route baseline: 57 NavKeys"
    # Prefer the agreed contract, but detect renamed matrix/parity documents too.
    candidates = []
    for path in sorted(apple.rglob("*.md")):
        if any(part.startswith(".") for part in path.relative_to(apple).parts):
            continue
        text = read_text(path)
        named = path.stem.lower() in ("parity", "route_matrix", "route-matrix", "routes")
        claims_matrix = re.search(r"^#{1,6}\s+.*(?:route[ /-]+matrix|screen[ /-]+matrix|route[ /-]+parity)", text, re.I | re.M)
        if named or claims_matrix:
            candidates.append((path, text))
    if not candidates:
        require(not required, "route matrix is required but absent; expected apple-native/Docs/PARITY.md")
        return False, f"matrix docs absent; {inventory}"
    baseline = canonical_routes(repo)
    for path, text in candidates:
        matching = [(headers, rows) for headers, rows in markdown_tables(text) if "route" in [h.lower().strip() for h in headers]]
        require(len(matching) == 1, f"{path}: expected one route table with columns Route | Status | Apple behavior / remaining work")
        headers, rows = matching[0]
        normalized = [h.lower().strip() for h in headers]
        require("status" in normalized and "apple behavior / remaining work" in normalized, f"{path}: expected columns Route | Status | Apple behavior / remaining work")
        route_column, status_column, behavior_column = (normalized.index(x) for x in ("route", "status", "apple behavior / remaining work"))
        routes = []
        for number, row in enumerate(rows, 1):
            route = code_cell(row[route_column], f"{path}:row {number}")
            routes.append(route)
            status = row[status_column].strip().strip("*`")
            require(status in ("Implemented", "Partial", "Missing"), f"{path}: {route}: invalid status {row[status_column]!r}")
            require(bool(row[behavior_column].strip()) and row[behavior_column].strip() not in ("-", "—", "TBD"), f"{path}: {route}: empty behavior/remaining-work description")
            # A Swift implementation path is optional in this document format.
            # When concrete Dart/Swift paths are supplied, they must resolve.
            for value in re.findall(r"`([^`]+)`", " ".join(row)):
                value = re.split(r"[:#]", value, maxsplit=1)[0]
                if value.endswith((".dart", ".swift")) and "/" in value:
                    if value.startswith("apple-native/") or value.startswith("lib/"):
                        target = repo / value
                    else:
                        target = apple / value
                    require(target.is_file(), f"{path}: {route}: missing referenced source {value}")
        duplicates = sorted(key for key, count in Counter(routes).items() if count > 1)
        require(not duplicates, f"{path}: duplicate routes: {duplicates}")
        require(len(routes) == 57, f"{path}: expected 57 route rows, found {len(routes)}")
        require(set(routes) == baseline, f"{path}: canonical route mismatch; missing={sorted(baseline - set(routes))}, extra={sorted(set(routes) - baseline)}")
    return True, f"{len(candidates)} matrix document(s), exact canonical route coverage; {inventory}"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--require-route-matrix", action="store_true", help="fail if the 57-route parity matrix is absent (CI)")
    args = parser.parse_args(argv)
    apple = Path(__file__).resolve().parents[1]
    errors = []

    def run(label, action):
        try:
            detail = action()
        except (Invalid, OSError, UnicodeError, ValueError, TypeError, RecursionError, ET.ParseError, plistlib.InvalidFileException) as error:
            errors.append((label, str(error)))
            print(f"FAIL {label}: {error}", file=sys.stderr)
            return False
        if detail is False:
            return True
        print(f"PASS {label}" + (f": {detail}" if detail else ""))
        return True

    project = None

    def parse_project():
        nonlocal project
        project = Project(apple)
        project.validate_graph()
        return f"{len(project.objects)} parsed objects; all typed references resolve and are reachable"

    if run("OpenStep project and object graph", parse_project):
        run("project paths and synchronized App membership", project.validate_paths)
        membership_ok = run("build phases, localization membership and local package", project.validate_membership)
        config_ok = run("Debug/Release effective settings for all three SDKs", lambda: validate_configurations(project))
        if config_ok:
            run("manual Info.plist and macOS entitlements", lambda: validate_plists(project))
        if membership_ok:
            run("localization syntax, duplicates and key parity", lambda: validate_localizations(project))
        run("shared scheme and app target IDs", lambda: validate_scheme(project))
    run("asset membership and PNG icon dimensions", lambda: validate_assets(apple))

    def check_matrix():
        present, detail = validate_route_matrix(apple, args.require_route_matrix)
        if not present:
            print(f"SKIP route matrix: {detail}")
            return False
        return detail

    run("route matrix", check_matrix)
    print(f"\n{'FAILED' if errors else 'OK'}: {len(errors)} validation error(s). Static checks only; Xcode build/signing and runtime behavior are not exercised.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
