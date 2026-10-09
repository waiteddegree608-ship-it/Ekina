import { api, USER_ID } from "./client";

export type UploadResult = {
  status: string;
  filename?: string;
  originalName?: string;
  absolutePath?: string;
  size?: number;
  message?: string;
};

export async function uploadChatFile(file: File, contactId: string): Promise<UploadResult> {
  const form = new FormData();
  form.append("file", file);
  form.append("contactId", contactId);
  form.append("userId", USER_ID);
  const { data } = await api.post<UploadResult>("/api/chat/upload", form, {
    // Let the browser set multipart boundary; do not force application/json
    headers: { "Content-Type": undefined as unknown as string },
    timeout: 180_000,
    transformRequest: [
      (body, headers) => {
        if (headers && typeof headers === "object") {
          delete (headers as Record<string, unknown>)["Content-Type"];
        }
        return body;
      },
    ],
  });
  return data;
}
