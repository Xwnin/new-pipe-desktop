package org.newpipe.desktop.data;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ListInfo;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Everything that talks to NewPipeExtractor, shaped as pageable {@link Loader}s of {@link Item}s. */
public final class Backend {
    public record Result(List<Item> items, Object next) {}

    public interface Loader {
        Result first() throws Exception;

        Result more(Object next) throws Exception;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "backend");
        t.setDaemon(true);
        return t;
    });

    private Backend() {}

    public static List<StreamingService> services() {
        return ServiceList.all();
    }

    public static StreamingService service(int id) {
        try {
            return NewPipe.getService(id);
        } catch (Exception e) {
            return ServiceList.YouTube;
        }
    }

    private static List<Item> convert(List<? extends InfoItem> list) {
        List<Item> out = new ArrayList<>();
        for (InfoItem i : list) {
            try {
                out.add(Item.from(i));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private static Result result(ListExtractor.InfoItemsPage<? extends InfoItem> p) {
        return new Result(convert(p.getItems()), p.hasNextPage() ? p.getNextPage() : null);
    }

    private static Result result(ListInfo<? extends InfoItem> info) {
        return new Result(convert(info.getRelatedItems()), info.hasNextPage() ? info.getNextPage() : null);
    }

    // ---- trending --------------------------------------------------------------------------

    public static Loader trending(int serviceId) {
        return new Loader() {
            String kioskUrl;

            @Override
            public Result first() throws Exception {
                StreamingService s = service(serviceId);
                KioskList kl = s.getKioskList();
                KioskExtractor<?> ex = kl.getDefaultKioskExtractor();
                kioskUrl = ex.getUrl();
                ex.fetchPage();
                return result(KioskInfo.getInfo(ex));
            }

            @Override
            @SuppressWarnings("unchecked")
            public Result more(Object next) throws Exception {
                return result(KioskInfo.getMoreItems(service(serviceId), kioskUrl, (Page) next));
            }
        };
    }

    /** Ids of the kiosks (Trending, Live, Top 50 ...) of a service, default first. */
    public static List<String> kioskIds(int serviceId) {
        try {
            KioskList kl = service(serviceId).getKioskList();
            List<String> ids = new ArrayList<>(kl.getAvailableKiosks());
            String def = kl.getDefaultKioskId();
            ids.remove(def);
            ids.add(0, def);
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Loader kiosk(int serviceId, String kioskId) {
        return new Loader() {
            String kioskUrl;

            @Override
            public Result first() throws Exception {
                KioskExtractor<?> ex = service(serviceId).getKioskList().getExtractorById(kioskId, null);
                kioskUrl = ex.getUrl();
                ex.fetchPage();
                return result(KioskInfo.getInfo(ex));
            }

            @Override
            public Result more(Object next) throws Exception {
                return result(KioskInfo.getMoreItems(service(serviceId), kioskUrl, (Page) next));
            }
        };
    }

    // ---- search ----------------------------------------------------------------------------

    public static List<String> searchFilters(int serviceId) {
        try {
            return service(serviceId).getSearchQHFactory().getAvailableContentFilter() == null ? List.of()
                    : List.of(service(serviceId).getSearchQHFactory().getAvailableContentFilter());
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Loader search(int serviceId, String query, String filter) {
        return new Loader() {
            SearchQueryHandler qh;

            @Override
            public Result first() throws Exception {
                StreamingService s = service(serviceId);
                qh = filter == null || filter.isEmpty()
                        ? s.getSearchQHFactory().fromQuery(query)
                        : s.getSearchQHFactory().fromQuery(query, List.of(filter), "");
                return result(SearchInfo.getInfo(s, qh));
            }

            @Override
            public Result more(Object next) throws Exception {
                return result(SearchInfo.getMoreItems(service(serviceId), qh, (Page) next));
            }
        };
    }

    public static List<String> suggestions(int serviceId, String query) {
        try {
            return service(serviceId).getSuggestionExtractor().suggestionList(query);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- channels / playlists --------------------------------------------------------------

    public static ChannelInfo channelInfo(String url) throws Exception {
        return ChannelInfo.getInfo(url);
    }

    public static String tabName(ListLinkHandler h) {
        List<String> f = h.getContentFilters();
        String n = f.isEmpty() ? "Videos" : f.get(0);
        return n.isEmpty() ? "Videos" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static ListLinkHandler videosTab(ChannelInfo ci) {
        for (ListLinkHandler h : ci.getTabs()) {
            List<String> f = h.getContentFilters();
            if (f.contains("videos") || f.contains("tracks")) return h;
        }
        return ci.getTabs().isEmpty() ? null : ci.getTabs().get(0);
    }

    public static Loader channelTab(int serviceId, ListLinkHandler tab) {
        return new Loader() {
            @Override
            public Result first() throws Exception {
                return result(ChannelTabInfo.getInfo(service(serviceId), tab));
            }

            @Override
            public Result more(Object next) throws Exception {
                return result(ChannelTabInfo.getMoreItems(service(serviceId), tab, (Page) next));
            }
        };
    }

    public static Loader playlist(String url) {
        return new Loader() {
            int sid;

            @Override
            public Result first() throws Exception {
                PlaylistInfo pi = PlaylistInfo.getInfo(url);
                sid = pi.getServiceId();
                return result(pi);
            }

            @Override
            public Result more(Object next) throws Exception {
                return result(PlaylistInfo.getMoreItems(service(sid), url, (Page) next));
            }
        };
    }

    public static Loader fixed(List<Item> items) {
        return new Loader() {
            @Override
            public Result first() {
                return new Result(new ArrayList<>(items), null);
            }

            @Override
            public Result more(Object next) {
                return new Result(List.of(), null);
            }
        };
    }

    // ---- feed ------------------------------------------------------------------------------

    private static List<Item> feedCache;
    private static long feedStamp;

    public static void invalidateFeed() {
        feedCache = null;
    }

    /** "What's new": the latest videos of every subscribed channel, newest first. */
    public static Loader feed() {
        return new Loader() {
            @Override
            public Result first() throws Exception {
                if (feedCache != null && System.currentTimeMillis() - feedStamp < 10 * 60_000) {
                    return new Result(new ArrayList<>(feedCache), null);
                }
                List<Item> subs = Store.subscriptions();
                List<Future<List<Item>>> futures = new ArrayList<>();
                for (Item sub : subs) {
                    futures.add(POOL.submit(() -> {
                        try {
                            ChannelInfo ci = ChannelInfo.getInfo(sub.url);
                            ListLinkHandler tab = videosTab(ci);
                            if (tab == null) return List.<Item>of();
                            ChannelTabInfo ti = ChannelTabInfo.getInfo(service(ci.getServiceId()), tab);
                            List<Item> out = new ArrayList<>();
                            for (InfoItem i : ti.getRelatedItems()) {
                                if (i instanceof StreamInfoItem s) {
                                    Item it = Item.from(s);
                                    if (it.uploader.isEmpty()) it.uploader = sub.name;
                                    if (it.uploaderUrl.isEmpty()) it.uploaderUrl = sub.url;
                                    out.add(it);
                                    if (out.size() >= 8) break;
                                }
                            }
                            return out;
                        } catch (Exception e) {
                            return List.<Item>of();
                        }
                    }));
                }
                List<Item> all = new ArrayList<>();
                for (Future<List<Item>> f : futures) all.addAll(f.get());
                all.sort(Comparator.comparingLong((Item i) -> i.time).reversed());
                feedCache = all;
                feedStamp = System.currentTimeMillis();
                return new Result(new ArrayList<>(all), null);
            }

            @Override
            public Result more(Object next) {
                return new Result(List.of(), null);
            }
        };
    }
}
