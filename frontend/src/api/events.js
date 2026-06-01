import client from './client';

export const getEvents = () => client.get('/api/events');
export const getEventDetail = (id) => client.get(`/api/events/${id}`);
