-- PocketIDE cloud setup: paste into the Supabase SQL editor once (SQL Editor > New query > Run).
-- Rows are private to the person who made them. Collections named shared:... can be read by everyone signed in,
-- and anyone signed in can post to them (chat rooms). Nobody can change or delete another person's rows.
create table if not exists app_items (
  id text primary key,
  owner uuid not null default auth.uid() references auth.users on delete cascade,
  collection text not null,
  data jsonb not null,
  updated_at timestamptz not null default now()
);
alter table app_items enable row level security;
create policy "own rows" on app_items for all using (auth.uid() = owner) with check (auth.uid() = owner);
create policy "shared rows readable when signed in" on app_items for select using (collection like 'shared:%' and auth.role() = 'authenticated');
create policy "signed-in people can post shared rows" on app_items for insert with check (collection like 'shared:%' and auth.uid() = owner);
