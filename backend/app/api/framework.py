"""Tiny typed-route layer over Starlette with automatic OpenAPI 3.1 generation.

FastAPI could not be installed in the build environment (package registry
blocked), so this ~150-line layer provides the parts we need from it on top of
Starlette (FastAPI's own foundation) and Pydantic v2: body/query validation,
sanitised 422 errors, response models and an OpenAPI document for Swagger UI.
"""
from __future__ import annotations

import inspect
import json
import re
from typing import Any, Callable, Optional, Type

from pydantic import BaseModel, ValidationError
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route


class HTTPError(Exception):
    def __init__(self, status: int, error: str, detail: Any = None):
        self.status, self.error, self.detail = status, error, detail


def clean_errors(e: ValidationError) -> list[dict]:
    # Do not echo submitted values back (they may contain sensitive context).
    return [{"loc": [str(x) for x in err["loc"]], "msg": err["msg"], "type": err["type"]} for err in e.errors()]


class API:
    def __init__(self, title: str, version: str, description: str):
        self.title, self.version, self.description = title, version, description
        self.routes: list[Route] = []
        self.ops: list[dict] = []
        self.models: dict[str, Type[BaseModel]] = {}

    def route(self, path: str, method: str = "GET", *, summary: str, tags: list[str], body: Optional[Type[BaseModel]] = None,
              query: Optional[Type[BaseModel]] = None, response: Optional[Type[BaseModel]] = None,
              status: int = 200, description: str = "", errors: tuple = (422,)):
        def deco(fn: Callable):
            async def endpoint(request: Request):
                kwargs = {}
                try:
                    if body is not None:
                        try:
                            data = await request.json()
                        except (json.JSONDecodeError, UnicodeDecodeError):
                            raise HTTPError(400, "Request body must be valid JSON")
                        kwargs["body"] = body.model_validate(data)
                    if query is not None:
                        kwargs["query"] = query.model_validate(dict(request.query_params))
                    kwargs.update(request.path_params)
                    if "request" in inspect.signature(fn).parameters:
                        kwargs["request"] = request
                    out = fn(**kwargs)
                    if inspect.isawaitable(out):
                        out = await out
                    if isinstance(out, JSONResponse):
                        return out
                    if isinstance(out, BaseModel):
                        out = out.model_dump(mode="json")
                    return JSONResponse(out, status_code=status)
                except ValidationError as e:
                    return JSONResponse({"error": "Validation failed", "detail": clean_errors(e),
                                         "request_id": request.scope.get("state", {}).get("request_id")}, status_code=422)
                except HTTPError as e:
                    return JSONResponse({"error": e.error, "detail": e.detail,
                                         "request_id": request.scope.get("state", {}).get("request_id")}, status_code=e.status)
            endpoint.__name__ = fn.__name__
            self.routes.append(Route(path, endpoint, methods=[method]))
            for m in (body, query, response):
                if m is not None:
                    self.models[m.__name__] = m
            self.ops.append({"path": path, "method": method.lower(), "summary": summary, "tags": tags, "body": body,
                             "query": query, "response": response, "status": status, "description": description or (fn.__doc__ or "").strip(),
                             "errors": errors})
            return fn
        return deco

    def openapi(self) -> dict:
        comps: dict[str, Any] = {}

        def ref(model):
            schema = model.model_json_schema(ref_template="#/components/schemas/{model}")
            for k, v in schema.pop("$defs", {}).items():
                comps[k] = v
            comps[model.__name__] = schema
            return {"$ref": f"#/components/schemas/{model.__name__}"}

        paths: dict[str, Any] = {}
        for op in self.ops:
            p = re.sub(r"\{(\w+)(:\w+)?\}", r"{\1}", op["path"])
            o: dict[str, Any] = {"summary": op["summary"], "tags": op["tags"], "description": op["description"],
                                 "responses": {}}
            params = [{"name": n, "in": "path", "required": True, "schema": {"type": "string"}}
                      for n in re.findall(r"\{(\w+)", op["path"])]
            if op["query"]:
                qs = op["query"].model_json_schema()
                for n, s in qs.get("properties", {}).items():
                    params.append({"name": n, "in": "query", "required": n in qs.get("required", []), "schema": s,
                                   "description": s.get("description", "")})
            if params:
                o["parameters"] = params
            if op["body"]:
                o["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(op["body"])}}}
            ok = {"description": "Success"}
            if op["response"]:
                ok["content"] = {"application/json": {"schema": ref(op["response"])}}
            o["responses"][str(op["status"])] = ok
            err = {"content": {"application/json": {"schema": {"type": "object", "properties": {
                "error": {"type": "string"}, "detail": {}, "request_id": {"type": "string"}}}}}}
            for code, desc in ((400, "Malformed request"), (404, "Not found"), (413, "Body too large"),
                               (422, "Validation error"), (429, "Rate limit exceeded")):
                if code in op["errors"] or code in (413, 429):
                    o["responses"][str(code)] = {"description": desc, **err}
            paths.setdefault(p, {})[op["method"]] = o
        return {"openapi": "3.1.0", "info": {"title": self.title, "version": self.version, "description": self.description},
                "paths": paths, "components": {"schemas": comps}}


SWAGGER_HTML = """<!doctype html><html><head><meta charset="utf-8"><title>NeuroShield API docs</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css"></head>
<body><div id="ui"></div><noscript>Swagger UI needs JavaScript and internet access; the raw spec is at /openapi.json</noscript>
<script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
<script>window.onload=()=>{ if(window.SwaggerUIBundle){SwaggerUIBundle({url:'/openapi.json',dom_id:'#ui'})}
else{document.getElementById('ui').innerHTML='<p style="font-family:sans-serif;padding:2rem">Swagger UI could not load (offline?). Open <a href="/openapi.json">/openapi.json</a>.</p>'}}</script>
</body></html>"""
