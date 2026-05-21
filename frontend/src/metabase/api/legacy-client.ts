import { api } from "./client";
export { NetworkError, type RequestMethod } from "./client";
export const { GET, POST, PUT, DELETE } = api;

/* eslint-disable-next-line import/no-default-export */
export default api;
