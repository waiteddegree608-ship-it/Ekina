import { api, USER_ID } from "./client";
import type { Contact } from "@/types/channel";

export async function fetchContacts(): Promise<Contact[]> {
  const { data } = await api.get<Contact[]>("/api/contacts", {
    params: { userId: USER_ID },
  });
  return Array.isArray(data) ? data : [];
}
