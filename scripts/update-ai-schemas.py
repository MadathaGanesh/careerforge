#!/usr/bin/env python3
"""Write CareerForge's pinned validation contracts and their TypeScript interfaces."""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "backend/src/main/resources/contracts"
TYPES = ROOT / "frontend/src/lib/contracts.generated.ts"


def obj(properties, optional=()):
    return {"type": "object", "additionalProperties": False,
            "required": [key for key in properties if key not in optional], "properties": properties}


def text(maximum, minimum=0):
    return {"type": "string", "maxLength": maximum, "minLength": minimum}


def choice(*values):
    return {"type": "string", "enum": list(values)}


def items(schema, maximum):
    return {"type": "array", "items": schema, "maxItems": maximum}


categories = ("MANDATORY", "EXPERIENCE", "RESPONSIBILITIES", "PREFERRED", "EDUCATION")
contracts = {
    "ResumeExtraction": ("resume-extract-v1", obj({
        "schemaVersion": choice("resume-extract-v1"),
        "candidate": obj({"name": text(200), "headline": text(300)}),
        "skills": items(text(120, 1), 200),
        "experiences": items(obj({"company": text(200), "role": text(200),
                                  "achievements": items(text(2000, 1), 100)}), 100),
        "warnings": items(text(1000, 1), 100),
        "mode": choice("GEMINI", "LOCAL_HEURISTIC"),
    }, optional=("mode",))),
    "JobExtraction": ("job-extract-v1", obj({
        "schemaVersion": choice("job-extract-v1"),
        "jobTitle": text(250),
        "requirements": items(obj({
            "id": text(120, 1), "label": text(250, 1),
            "priority": choice("MANDATORY", "PREFERRED"), "sourceQuote": text(2000, 1),
            "category": choice(*categories),
        }, optional=("category",)), 200),
        "responsibilities": items(text(2000, 1), 150),
        "minExperienceYears": {"type": ["number", "null"], "minimum": 0, "maximum": 80},
        "mode": choice("GEMINI", "LOCAL_HEURISTIC"),
    }, optional=("mode", "minExperienceYears"))),
    "TailoringProposals": ("tailoring-proposals-v2", obj({"edits": items(obj({
        "originalText": text(2000, 8), "proposedText": text(2000, 1), "reason": text(2000, 1),
        "evidence": text(2000, 1),
    }), 8)})),
    "InterviewQuestions": ("interview-questions-v2", obj({"questions": {
        **items(obj({"category": choice("TECHNICAL", "BEHAVIORAL", "HR", "RESUME", "SYSTEM_DESIGN"),
                     "question": text(1500, 1), "suggestedAnswer": text(3000, 1)}), 12), "minItems": 1,
    }})),
    "InterviewFeedback": ("interview-feedback-v2", obj({
        "score": {"type": "number", "minimum": 0, "maximum": 100},
        "strengths": items(text(1000, 1), 20), "improvements": items(text(1000, 1), 20),
        "suggestedAnswer": text(3000, 1),
    })),
    "FitAssessment": ("fit-v3", obj({
        "rubricVersion": choice("fit-v3"), "mode": choice("LOCAL_HEURISTIC"), "scoreKind": choice("HEURISTIC_ESTIMATE"),
        "alignmentScore": {"type": "number", "minimum": 0, "maximum": 100},
        "requirements": items(obj({
            "requirementId": text(120, 1), "label": text(250, 1), "priority": choice("MANDATORY", "PREFERRED"),
            "category": choice(*categories),
            "status": choice("DEMONSTRATED", "PARTIAL", "UNVERIFIED", "MISSING", "NOT_APPLICABLE"),
            "evidence": items(obj({
                "sourcePath": choice("/contentText"),
                "start": {"type": "integer", "minimum": 0}, "end": {"type": "integer", "minimum": 1},
                "excerpt": text(1000, 1),
            }), 30),
            "rationale": text(2000, 1),
        }), 351),
        "categoryBreakdown": items(obj({
            "category": choice(*categories), "weight": {"type": "number", "minimum": 0, "maximum": 100},
            "score": {"type": "number", "minimum": 0, "maximum": 100},
        }), 5),
        "excludedCategories": items(choice(*categories), 5),
        "disclaimer": text(2000, 1),
    })),
}


def ts(schema, indent=""):
    if "enum" in schema:
        return " | ".join(map(json.dumps, schema["enum"]))
    kind = schema["type"]
    if isinstance(kind, list):
        return " | ".join(ts({"type": variant}, indent) for variant in kind)
    if kind == "array":
        return "Array<" + ts(schema["items"], indent) + ">"
    if kind == "object":
        nested = indent + "  "
        props = []
        for key, value in schema["properties"].items():
            opt = "?" if key not in schema["required"] else ""
            props.append(nested + key + opt + ": " + ts(value, nested) + ";")
        return "{\n" + "\n".join(props) + "\n" + indent + "}"
    return {"integer": "number"}.get(kind, kind)


def update(file, content):
    if "--check" in sys.argv:
        if not file.is_file() or file.read_text() != content:
            raise SystemExit(f"Generated contract is out of date: {file.relative_to(ROOT)}")
    else:
        file.write_text(content)


for name, (version, schema) in contracts.items():
    update(CONTRACTS / (version + ".json"),
           json.dumps({"$schema": "https://json-schema.org/draft/2020-12/schema", "title": name, **schema}, indent=2) + "\n")
update(TYPES, "// Generated by scripts/update-ai-schemas.py. Edit the generator.\n\n" +
       "\n\n".join("export type " + name + " = " + ts(schema) + ";" for name, (_, schema) in contracts.items()) + "\n")
