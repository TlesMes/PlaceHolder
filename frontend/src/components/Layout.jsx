import Header from './Header';

export default function Layout({ children }) {
  return (
    <div className="min-h-screen bg-base">
      <Header />
      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6">{children}</main>
    </div>
  );
}
