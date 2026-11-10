'use strict';

const {randomUUID} = require('node:crypto');

function database(gateway, apiKey) {
  const root = gateway + '/v1/database/instances/(default)/databases/(default)/collections/studytv_remote/documents';
  async function request(url, method, body) {
    const response = await fetch(url, {
      method,
      headers: {Authorization: 'Bearer ' + apiKey, 'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      signal: AbortSignal.timeout(4500)
    });
    if (!response.ok) throw new Error('CloudBase database HTTP ' + response.status);
    return response.json();
  }
  return {
    async read(id) {
      const document = await request(root + '/' + id, 'GET');
      const record = document.doc ?? document.data?.doc ?? document.data ?? document;
      if (record._id !== id) throw new Error('Invalid CloudBase document');
      return record;
    },
    async update(id, match, values) {
      const result = await request(root, 'PATCH', {
        query: {_id: id, ...match},
        data: {$set: {...values, revision: randomUUID()}},
        multi: false, upsert: false, replaceMode: false
      });
      const updated = result.updated ?? result.data?.updated;
      if (updated === undefined) throw new Error('Invalid CloudBase update response');
      return Number(updated.$numberInt ?? updated.$numberLong ?? updated) === 1;
    }
  };
}

module.exports = {database};
