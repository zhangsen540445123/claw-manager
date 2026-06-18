import { Client, type IMessage } from "@stomp/stompjs";
import { useAdminStore } from "../stores/admin";
import type { AppEvent } from "../api/types";

let client: Client | null = null;

export function connectAppWebSocket() {
  const admin = useAdminStore();
  if (client?.active) {
    return;
  }

  client = new Client({
    brokerURL: wsUrl("/ws"),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      admin.wsConnected = true;
      subscribe("/topic/admin/instances");
      subscribe("/topic/admin/instance-stats");
      subscribe("/topic/admin/wechat");
      subscribe("/topic/admin/model-auth");
      subscribe("/topic/admin/runner-image");
      void admin.loadInstances();
      void admin.loadRunnerImage();
    },
    onWebSocketClose: () => {
      admin.wsConnected = false;
    },
    onStompError: () => {
      admin.wsConnected = false;
    }
  });
  client.activate();
}

export function disconnectAppWebSocket() {
  const admin = useAdminStore();
  admin.wsConnected = false;
  if (client) {
    void client.deactivate();
    client = null;
  }
}

function subscribe(destination: string) {
  client?.subscribe(destination, (message: IMessage) => {
    const event = JSON.parse(message.body) as AppEvent;
    const admin = useAdminStore();
    admin.applyEvent(event);
  });
}

function wsUrl(path: string) {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}${path}`;
}
