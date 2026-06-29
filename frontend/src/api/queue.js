import client from './client';

export const enterQueue = (eventId) => client.post(`/api/queue/${eventId}/enter`);
export const getQueueStatus = (eventId) => client.get(`/api/queue/${eventId}/status`);
