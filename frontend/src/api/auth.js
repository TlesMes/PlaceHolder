import client from './client';

export const signup = (data) => client.post('/api/auth/signup', data);
export const login = (data) => client.post('/api/auth/login', data);
