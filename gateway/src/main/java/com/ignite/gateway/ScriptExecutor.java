package com.ignite.gateway;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.python.core.PyObject;
import org.python.core.PyString;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Executes Ignition project library scripts and system.* functions via Jython/ScriptManager.
 *
 * Uses a bounded thread pool (4 threads, 8 queued) with a per-call timeout. When the pool and
 * queue are full, {@link SaturatedException} is thrown.
 *
 * Security notes (ADR-004): write-tier callers can invoke any system.* function; that is an
 * accepted trade-off and the tier is not narrowed here. The timeout is response-only: on timeout
 * the worker thread is interrupted, not killed, so a runaway script may keep running.
 * Function paths are validated against a strict identifier regex and resolved Java-side; they
 * are never concatenated into Python source.
 *
 * Results are serialized via a 3-tier fallback:
 *   Tier 1: TypeUtilities.pyToGson()
 *   Tier 2: Sequence extraction (__len__ / __getitem__)
 *   Tier 3: String fallback with type annotation
 */
public class ScriptExecutor {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.ScriptExecutor");

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final Pattern FUNCTION_PATH =
            Pattern.compile("^[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+$");

    /** Thrown when the executor pool and queue are full. */
    public static class SaturatedException extends RuntimeException {
        public SaturatedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final GatewayContext gatewayContext;
    private final ExecutorService executorService;

    public ScriptExecutor(GatewayContext ctx) {
        this.gatewayContext = ctx;
        final AtomicInteger counter = new AtomicInteger();
        this.executorService = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r, "ignite-script-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Execute a project library function.
     *
     * @param projectName  Ignition project name (e.g., "MyProject")
     * @param functionPath Dotted path to function (e.g., "demo.helloWorld")
     * @param args         Positional arguments as JsonArray (may be null)
     * @param kwargs       Keyword arguments as JsonObject (may be null)
     * @return Result as JsonObject: {"result": ...} or {"error": "..."}
     */
    public JsonObject executeLibraryFunction(String projectName, String functionPath,
                                              JsonArray args, JsonObject kwargs) throws Exception {
        requireValidPath(functionPath);
        return executeCode(null, functionPath, projectName, "library:" + functionPath, args, kwargs);
    }

    /**
     * Execute a system.* function.
     *
     * @param projectName  Project for ScriptManager context
     * @param functionName Full function name (e.g., "system.tag.readBlocking")
     * @param args         Positional arguments as JsonArray (may be null)
     * @param kwargs       Keyword arguments as JsonObject (may be null)
     * @return Result as JsonObject
     */
    public JsonObject executeSystemFunction(String projectName, String functionName,
                                             JsonArray args, JsonObject kwargs) throws Exception {
        requireValidPath(functionName);
        if (!functionName.startsWith("system.")) {
            throw new IllegalArgumentException("invalid function path");
        }
        return executeCode(null, functionName, projectName, "system:" + functionName, args, kwargs);
    }

    /**
     * Execute a raw Jython script with keyword arguments.
     * The script must assign to a variable named "result".
     *
     * @param projectName  Project for ScriptManager context
     * @param code         Python code to execute (must assign to "result")
     * @param kwargs       Keyword arguments accessible as _kwargs in the script
     * @return Result as JsonObject: {"result": ...}
     */
    public JsonObject executeScript(String projectName, String code, JsonObject kwargs) throws Exception {
        return executeCode(code, null, projectName, "script:custom", null, kwargs);
    }

    /**
     * List all running project names on this gateway.
     */
    public List<String> getProjectNames() {
        return gatewayContext.getProjectManager().getNames();
    }

    /**
     * Shutdown executor on module unload.
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Core execution logic. Submits script to thread pool with a timeout.
     *
     * @param pythonCode   Fixed Python code to execute (must assign to "result"); null when fnPath is used
     * @param fnPath       Validated dotted function path resolved Java-side; null for raw scripts
     * @param projectName  Project to resolve ScriptManager from
     * @param contextLabel Label for logging/error messages
     * @param args         Positional args (null → empty list)
     * @param kwargs       Keyword args (null → empty dict)
     */
    private JsonObject executeCode(String pythonCode, String fnPath, String projectName,
                                   String contextLabel, JsonArray args, JsonObject kwargs) throws Exception {
        final JsonArray safeArgs   = args   != null ? args   : new JsonArray();
        final JsonObject safeKwargs = kwargs != null ? kwargs : new JsonObject();

        Future<JsonObject> future;
        try {
            future = executorService.submit(() -> {
            ScriptManager scriptManager = getScriptManager(projectName);

            // Create a locals map — pre-imports system.* into scope
            PyObject locals = scriptManager.createLocalsMap();

            // Inject args and kwargs as Python-native objects
            locals.__setitem__("_args",   TypeUtilities.gsonToPy(safeArgs));
            locals.__setitem__("_kwargs", TypeUtilities.gsonToPy(safeKwargs));

            // Execute
            if (fnPath != null) {
                locals.__setitem__("_fn", resolveFunction(scriptManager, locals, fnPath, contextLabel));
                scriptManager.runCode("result = _fn(*_args, **_kwargs)\n", locals, contextLabel + ".py");
            } else {
                scriptManager.runCode(pythonCode, locals, contextLabel + ".py");
            }

            // Extract result
            PyObject resultPy = locals.__finditem__("result");
            if (resultPy == null) {
                throw new Exception("Script did not produce a 'result' variable");
            }

            // 3-tier serialization fallback
            JsonElement resultEl = serializePyObject(resultPy);

            JsonObject out = new JsonObject();
            out.add("result", resultEl);
            return out;
            });
        } catch (RejectedExecutionException e) {
            throw new SaturatedException("script executor busy", e);
        }

        try {
            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Script execution timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds");
        } catch (ExecutionException e) {
            // Unwrap the actual exception thrown in the callable
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new Exception("Script execution failed: " + cause.getMessage(), cause);
        }
    }

    private static void requireValidPath(String path) {
        if (path == null || !FUNCTION_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid function path");
        }
    }

    /**
     * Resolve a validated dotted path to a callable without building Python source.
     * The root name is looked up in the locals map; if not pre-bound it is imported via a
     * constant code string that reads the name from locals. Remaining segments use getattr.
     */
    private PyObject resolveFunction(ScriptManager sm, PyObject locals, String path, String label) throws Exception {
        requireValidPath(path);
        String[] parts = path.split("\\.");
        PyObject obj = locals.__finditem__(parts[0]);
        if (obj == null) {
            locals.__setitem__("_root_name", new PyString(parts[0]));
            sm.runCode("_root = __import__(_root_name)\n", locals, label + ".import.py");
            obj = locals.__finditem__("_root");
        }
        if (obj == null) {
            throw new IllegalArgumentException("Unable to resolve function path");
        }
        for (int i = 1; i < parts.length; i++) {
            obj = obj.__getattr__(parts[i]);
        }
        return obj;
    }

    /**
     * Resolve a ScriptManager. A null/blank projectName selects the gateway-scope manager
     * (system.* is available there).
     *
     * @throws IllegalArgumentException if the named project is not found
     */
    private ScriptManager getScriptManager(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return gatewayContext.getScriptManager();
        }
        ScriptManager sm = gatewayContext.getProjectManager().getProjectScriptManager(projectName);
        if (sm == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return sm;
    }

    /**
     * Convert a Python object to a JsonElement using a 3-tier fallback strategy.
     *
     * Tier 1: TypeUtilities.pyToGson() — handles primitives, lists, dicts, datasets, QVs, etc.
     * Tier 2: Sequence extraction via __len__ / __getitem__ — handles list-like objects.
     * Tier 3: String fallback with type annotation.
     */
    private JsonElement serializePyObject(PyObject pyObj) {
        // Tier 1
        try {
            return TypeUtilities.pyToGson(pyObj);
        } catch (Exception e) {
            log.debug("pyToGson() failed (" + e.getMessage() + "), trying sequence extraction");
        }

        // Tier 2 — sequence extraction
        try {
            int length = pyObj.__len__();
            JsonArray array = new JsonArray();
            for (int i = 0; i < length; i++) {
                PyObject item = pyObj.__getitem__(i);
                try {
                    array.add(TypeUtilities.pyToGson(item));
                } catch (Exception e) {
                    JsonObject itemFallback = new JsonObject();
                    itemFallback.addProperty("value", item.toString());
                    itemFallback.addProperty("type", item.getType().getName());
                    array.add(itemFallback);
                }
            }
            return array;
        } catch (Exception e) {
            log.debug("Sequence extraction failed (" + e.getMessage() + "), using string fallback");
        }

        // Tier 3 — string fallback
        JsonObject fallback = new JsonObject();
        fallback.addProperty("result_string", pyObj.toString());
        fallback.addProperty("result_type", pyObj.getType().getName());
        return fallback;
    }
}
