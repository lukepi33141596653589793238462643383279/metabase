/* eslint-disable metabase/no-literal-metabase-strings */
import EventEmitter from "events";
import querystring from "querystring";

import { substituteUrlTags } from "metabase/api/utils/substitute-url-tags";
import { isEmbeddingSdk } from "metabase/embedding-sdk/config";
import { PLUGIN_API, PLUGIN_EMBEDDING_SDK } from "metabase/plugins";
import type {
  OnBeforeRequestHandler,
  OnBeforeRequestHandlerConfig,
} from "metabase/plugins/oss/api";
import { IFRAMED_IN_SELF, isWithinIframe } from "metabase/utils/iframe";
import { getTraceparentHeader } from "metabase/utils/otel";
import { retry } from "metabase/utils/retry";

const MAX_RETRIES = 10;

const ANTI_CSRF_HEADER = "X-Metabase-Anti-CSRF-Token";
const METABASE_VERSION_HEADER = "X-Metabase-Version";

let ANTI_CSRF_TOKEN: string | null = null;
let LOCALE: string | null = null;

type ResponseTransformer = (opts: {
  body: object;
  data?: Record<string, unknown>;
  response?: Response;
}) => Response | undefined;

type RequestOptions = {
  noEvent?: boolean;
  transformResponse?: ResponseTransformer;
  headers?: Record<string, string>;
  signal?: AbortSignal;
};

type RequestClientInfo = string | { name: string; version: string | null };

/**
 * Legacy API method. Consumers across the codebase pass concrete request shapes
 * (e.g. `CreateDashboardRequest`) and rely on destructuring a concrete response,
 * so we use broad `any` types here to match the JS version's behaviour.
 */
type ApiMethod = (
  rawData?: any,
  invocationOptions?: RequestOptions,
) => Promise<any>;

type MethodCreator = (
  urlTemplate: string,
  methodOptions?: RequestOptions | ResponseTransformer,
) => ApiMethod;

type ResponseErrorInfo = {
  body: unknown;
  status: number;
  metabaseVersion: string | null;
};

/**
 * Thrown when the transport itself fails before a response is received —
 * e.g. the server dropped the connection, DNS lookup failed, or the user is
 * offline. Callers can `instanceof`-check this to render a connectivity
 * error message instead of treating it as a generic JS exception.
 */
export class NetworkError extends Error {
  constructor(message = "Network error") {
    super(message);
    this.name = "NetworkError";
  }
}

export class LegacyApi extends EventEmitter {
  basename = "";
  apiKey = "";
  sessionToken: string | undefined;
  onResponseError: ((info: ResponseErrorInfo) => void) | undefined;
  requestClient: RequestClientInfo | undefined;

  beforeRequestHandlers: OnBeforeRequestHandler[] = [];

  GET: MethodCreator;
  POST: MethodCreator;
  PUT: MethodCreator;
  DELETE: MethodCreator;

  constructor() {
    super();
    this.GET = this._makeMethod("GET", true);
    this.DELETE = this._makeMethod("DELETE", false);
    this.POST = this._makeMethod("POST", true);
    this.PUT = this._makeMethod("PUT", false);
  }

  getClientHeaders(): Record<string, string> {
    const self = this;
    const headers: Record<string, string> = {
      Accept: "application/json",
      "Content-Type": "application/json",
    };

    if (this.apiKey) {
      headers["X-Api-Key"] = self.apiKey;
    }

    if (this.sessionToken) {
      headers["X-Metabase-Session"] = self.sessionToken!;
    }

    if (isWithinIframe() && !isEmbeddingSdk()) {
      headers["X-Metabase-Embedded"] = "true";
    }

    if (self.requestClient) {
      if (IFRAMED_IN_SELF) {
        headers["X-Metabase-Embedded-Preview"] = "true";
      }
      if (typeof self.requestClient === "object") {
        headers["X-Metabase-Client"] = self.requestClient.name;
        if (self.requestClient.version) {
          headers["X-Metabase-Client-Version"] = self.requestClient.version;
        }
      } else {
        headers["X-Metabase-Client"] = self.requestClient;
      }
    }

    if (ANTI_CSRF_TOKEN) {
      headers[ANTI_CSRF_HEADER] = ANTI_CSRF_TOKEN;
    }

    if (LOCALE) {
      headers["X-Metabase-Locale"] = LOCALE;
    }

    const traceparent = getTraceparentHeader();
    if (traceparent) {
      headers["traceparent"] = traceparent;
    }

    return headers;
  }

  _makeMethod(methodTemplate: string, retry: boolean = false): MethodCreator {
    return (urlTemplate, methodOptions = {}) => {
      if (typeof methodOptions === "function") {
        methodOptions = { transformResponse: methodOptions };
      }

      return async (rawData = {}, invocationOptions = {}) => {
        const middlewareResult = await this.apiRequestManipulationMiddleware({
          url: urlTemplate,
          method: methodTemplate as "GET" | "POST",
          options: {
            ...methodOptions,
            ...invocationOptions,
          },
          // this will transform arrays to objects with numeric keys
          // we shouldn't be using top level-arrays in the API
          data: { ...rawData },
        });
        let { url, method } = middlewareResult;
        // Re-merge to preserve all RequestOptions fields after middleware (middleware can only extend options)
        const options = {
          ...methodOptions,
          ...invocationOptions,
          ...middlewareResult.options,
        };
        const { data } = middlewareResult;
        url = substituteUrlTags(url, data, method);
        // remove undefined
        for (const name in data) {
          if (data[name] === undefined) {
            delete data[name];
          }
        }

        // Method-derived placement: POST/PUT/DELETE put data in body, GET
        // puts it in the querystring. Callers wanting RTK-style explicit
        // body/params semantics use `request()` below instead.
        let body: string | undefined;
        if (method === "GET") {
          const qs = querystring.stringify(data as Record<string, string>);
          if (qs) {
            url += (url.indexOf("?") >= 0 ? "&" : "?") + qs;
          }
        } else if (Object.keys(data).length > 0) {
          body = JSON.stringify(data);
        }

        const headers = this._buildHeaders(options);

        return this._dispatch({
          method,
          url,
          headers,
          body,
          data,
          options,
          retry,
        });
      };
    };
  }

  /**
   * RTK Query entry point with explicit body/params semantics:
   * - `body`: sent as the request body. `FormData` / `URLSearchParams` are
   *   forwarded as-is so the browser sets the right Content-Type. Anything
   *   else is `JSON.stringify`'d. For `GET` it's folded into the querystring.
   * - `params`: URL `:tag` substitution first, leftover keys become querystring.
   *
   * No method-derived guesswork about whether data is body or querystring.
   */
  async request({
    method,
    url: urlTemplate,
    body: requestBody,
    params,
    signal,
    noEvent,
    transformResponse,
    headers: headerOverrides,
  }: {
    method: "GET" | "POST" | "PUT" | "DELETE";
    url: string;
    body?: unknown;
    params?: Record<string, unknown>;
    signal?: AbortSignal;
    noEvent?: boolean;
    transformResponse?: ResponseTransformer;
    headers?: Record<string, string>;
  }): Promise<unknown> {
    const invocationOptions = {
      signal,
      ...(noEvent !== undefined ? { noEvent } : {}),
      ...(transformResponse ? { transformResponse } : {}),
      ...(headerOverrides ? { headers: headerOverrides } : {}),
    };

    const middlewareResult = await this.apiRequestManipulationMiddleware({
      url: urlTemplate,
      method: method as "GET" | "POST",
      options: {
        ...invocationOptions,
      } as OnBeforeRequestHandlerConfig["options"],
      data: { ...params },
    });

    let { url, method: finalMethod } = middlewareResult;
    const options = {
      ...invocationOptions,
      ...middlewareResult.options,
    };
    const { data } = middlewareResult;

    url = substituteUrlTags(url, data, finalMethod);
    for (const name in data) {
      if (data[name] === undefined) {
        delete data[name];
      }
    }

    let body: string | FormData | URLSearchParams | undefined;
    const queryStringRecord: Record<string, unknown> = { ...data };
    const bodyIsRaw =
      requestBody instanceof FormData || requestBody instanceof URLSearchParams;

    if (finalMethod === "GET") {
      // GET cannot carry a body: fold any body content into the querystring.
      Object.assign(
        queryStringRecord,
        requestBody as Record<string, unknown> | undefined,
      );
    } else if (bodyIsRaw) {
      body = requestBody as FormData | URLSearchParams;
    } else if (requestBody !== undefined) {
      body = JSON.stringify(requestBody);
    }

    const qs = querystring.stringify(
      queryStringRecord as Record<string, string>,
    );
    if (qs) {
      url += (url.indexOf("?") >= 0 ? "&" : "?") + qs;
    }

    const headers = this._buildHeaders(options);
    if (bodyIsRaw) {
      // Let the browser set Content-Type with the multipart boundary
      // (FormData) or urlencoded charset (URLSearchParams).
      delete headers["Content-Type"];
    }

    // RTK callers don't retry; matches the prior behavior where apiQuery never
    // set `retry: true`.
    return this._dispatch({
      method: finalMethod,
      url,
      headers,
      body,
      data,
      options,
      retry: false,
    });
  }

  _buildHeaders(options: RequestOptions): Record<string, string> {
    return {
      ...this.getClientHeaders(),
      ...options.headers,
    };
  }

  _dispatch({
    method,
    url,
    headers,
    body,
    data,
    options,
    retry,
  }: {
    method: string;
    url: string;
    headers: Record<string, string>;
    body: string | FormData | URLSearchParams | undefined;
    data: Record<string, unknown>;
    options: RequestOptions;
    retry: boolean;
  }): Promise<unknown> {
    if (retry) {
      return this._makeRequestWithRetries(
        method,
        url,
        headers,
        body,
        data,
        options,
      );
    }
    return this._makeRequest(method, url, headers, body, data, options);
  }

  async _makeRequestWithRetries(
    method: string,
    url: string,
    headers: Record<string, string>,
    body: string | FormData | URLSearchParams | undefined,
    data: Record<string, unknown>,
    options: RequestOptions,
  ): Promise<unknown> {
    // Attempt the request; on 503 retry up to MAX_RETRIES times with
    // exponential backoff (1s, 2s, 4s, 8s, ...).
    return retry(
      () => this._makeRequest(method, url, headers, body, data, options),
      {
        maxRetries: MAX_RETRIES,
        shouldRetry: (error) => getErrorStatus(error) === 503,
      },
    );
  }

  async _makeRequest(
    method: string,
    url: string,
    headers: Record<string, string>,
    requestBody: string | FormData | URLSearchParams | undefined,
    data: Record<string, unknown>,
    options: RequestOptions,
  ): Promise<unknown> {
    const requestUrl = new URL(this.basename + url, location.origin);
    const request = new Request(requestUrl.href, {
      method,
      headers,
      body: requestBody,
      signal: options.signal,
    });

    // Propagate aborts from an externally-supplied signal. If the signal is
    // already aborted (e.g. cancelled while we were awaiting auth-refresh
    // middleware), defer the propagation to the next microtask so the request
    // still gets dispatched first — matching XHR's `send()` then-`abort()`
    // semantics, where `xhr.send()` runs before the cancel handler is wired
    // up. Otherwise the network never sees the request at all.
    if (options.signal) {
      if (options.signal.aborted) {
        queueMicrotask(() => controller.abort());
      } else {
        options.signal.addEventListener("abort", () => controller.abort());
      }
    }

    return fetch(request)
      .then((response) => {
        const unreadResponse = response.clone();
        return response.text().then((bodyText) => {
          let body: string | Response | undefined = bodyText;

          try {
            body = JSON.parse(bodyText);
          } catch (e) {}

          let status = response.status;
          if (
            status === 202 &&
            body &&
            typeof body === "object" &&
            "_status" in body &&
            body._status &&
            (body._status as number) > 0
          ) {
            status = (body as Record<string, number>)._status;
          }

          const token = response.headers.get(ANTI_CSRF_HEADER);
          const metabaseVersion = response.headers.get(METABASE_VERSION_HEADER);

          if (token) {
            ANTI_CSRF_TOKEN = token;
          }

          if (!options.noEvent) {
            this.emit(String(status), url);
          }

          if (status >= 200 && status <= 299) {
            if (options.transformResponse) {
              body = options.transformResponse({
                body: body as Response,
                data,
                response: unreadResponse,
              });
            }
            return body;
          } else {
            if (this.onResponseError) {
              this.onResponseError({ body, status, metabaseVersion });
            }

            throw { status: status, data: body };
          }
        });
      })
      .catch((error: unknown) => {
        if (options.signal?.aborted) {
          throw { isCancelled: true };
        }
        // A raw `fetch` rejection (e.g. the server dropped the connection)
        // surfaces as a plain Error here, indistinguishable from JS
        // exceptions thrown elsewhere. Wrap it so downstream renderers can
        // `instanceof NetworkError`-check and route it to the connectivity
        // error message.
        if (error instanceof Error) {
          throw new NetworkError(error.message);
        }
        throw error;
      });
  }

  async apiRequestManipulationMiddleware(
    requestConfig: OnBeforeRequestHandlerConfig,
  ): Promise<OnBeforeRequestHandlerConfig> {
    let { method, url, options, data } = requestConfig;

    /**
     * Handlers order is important.
     * Handlers are executed in order and each handler uses the data returned by a previous handler.
     */
    const handlers: Array<
      (
        data: OnBeforeRequestHandlerConfig,
      ) => Promise<void | OnBeforeRequestHandlerConfig>
    > = [];

    if (isEmbeddingSdk()) {
      handlers.push(
        ...[
          PLUGIN_EMBEDDING_SDK.onBeforeRequestHandlers
            .getOrRefreshSessionHandler,
          PLUGIN_EMBEDDING_SDK.onBeforeRequestHandlers
            .getOrRefreshGuestSessionHandler,
          PLUGIN_EMBEDDING_SDK.onBeforeRequestHandlers
            .overrideRequestsForGuestEmbeds,
        ],
      );
    } else {
      handlers.push(
        ...[
          PLUGIN_API.onBeforeRequestHandlers.overrideRequestsForPublicEmbeds,
          PLUGIN_API.onBeforeRequestHandlers.overrideRequestsForStaticEmbeds,
        ],
      );
    }

    handlers.push(...this.beforeRequestHandlers);

    if (handlers.length) {
      for (const handler of handlers) {
        const onBeforeRequestHandlerResult = await handler({
          method,
          url,
          options,
          data,
        });

        if (onBeforeRequestHandlerResult) {
          if (onBeforeRequestHandlerResult.method) {
            method = onBeforeRequestHandlerResult.method;
          }

          if (onBeforeRequestHandlerResult.url) {
            url = onBeforeRequestHandlerResult.url;
          }

          if (onBeforeRequestHandlerResult.options) {
            options = {
              ...options,
              ...onBeforeRequestHandlerResult.options,
            };
          }

          if (onBeforeRequestHandlerResult.data) {
            data = {
              ...data,
              ...onBeforeRequestHandlerResult.data,
            };
          }
        }
      }
    }

    return { method, url, options, data };
  }
}

const instance = new LegacyApi();

// eslint-disable-next-line import/no-default-export -- deprecated usage
export default instance;
export const { GET, POST, PUT, DELETE } = instance;

export const setLocaleHeader = (locale: string | null | undefined): void => {
  /* `X-Metabase-Locale` is a header that the BE stores as *user* locale for the scope of the request.
   * We need it to localize downloads. It *currently* only work if there is a user, so it won't work
   * for public/static embedding.
   */
  LOCALE = locale ?? null;
};

function getErrorStatus(error: unknown): number | undefined {
  if (
    typeof error === "object" &&
    error !== null &&
    "status" in error &&
    typeof error.status === "number"
  ) {
    return error.status;
  }
  return undefined;
}
