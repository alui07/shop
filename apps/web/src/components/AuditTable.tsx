import { Link } from '@tanstack/react-router';
import type { components } from '@/api/schema';
import { formatDateTime } from '@/lib/dateTime';

type AuditEvent = components['schemas']['AuditEvent'];

function Value({ value }: { value: string | null }) {
  return <bdi>{value ?? '(none)'}</bdi>;
}

export function AuditTable({ events }: { events: readonly AuditEvent[] }) {
  return (
    <table className="w-full text-left text-sm">
      <caption className="sr-only">Changes made by administrators, newest first</caption>
      <thead>
        <tr className="border-b">
          <th scope="col" className="py-2 pr-4">
            Time
          </th>
          <th scope="col" className="py-2 pr-4">
            Actor
          </th>
          <th scope="col" className="py-2 pr-4">
            Action
          </th>
          <th scope="col" className="py-2 pr-4">
            Product
          </th>
          <th scope="col" className="py-2 pr-4">
            Changes
          </th>
          <th scope="col" className="py-2">
            Trace (unverified)
          </th>
        </tr>
      </thead>
      <tbody>
        {events.map((event) => (
          <tr key={event.id} className="border-b align-top">
            <td className="py-2 pr-4">
              <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
            </td>
            <td className="py-2 pr-4">
              <bdi>{event.actorName}</bdi>
            </td>
            <td className="py-2 pr-4">{event.action}</td>
            <td className="py-2 pr-4">
              <Link
                to="/admin/products/$id"
                params={{ id: String(event.entityId) }}
                className="underline"
              >
                Product {event.entityId}
              </Link>
            </td>
            <td className="py-2 pr-4">
              <ul>
                {event.changes.map((change) => (
                  <li key={change.field}>
                    {change.field}: <Value value={change.from} /> → <Value value={change.to} />
                  </li>
                ))}
              </ul>
            </td>
            <td className="py-2">
              {event.traceId === null ? 'none' : <code>{event.traceId}</code>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
