import client from './client';

export const getEvents = () => client.get('/api/events');
export const getEventDetail = (id) => client.get(`/api/events/${id}`);
export const createEvent = (data) => client.post('/api/events', data);
