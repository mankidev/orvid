package com.orvid.yt;

import com.orvid.net.Fetch;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

public final class WebSearch {

    private WebSearch() {
    }

    public static List<StreamInfoItem> pages(String circuit, String query, int pages) throws Exception {
        return Fetch.on(circuit, () -> {
            List<StreamInfoItem> out = new ArrayList<>();
            SearchExtractor ex = ServiceList.YouTube.getSearchExtractor(query);
            ex.fetchPage();
            ListExtractor.InfoItemsPage<InfoItem> page = ex.getInitialPage();
            for (int i = 0; ; i++) {
                for (InfoItem it : page.getItems()) {
                    if (it instanceof StreamInfoItem) out.add((StreamInfoItem) it);
                }
                if (i + 1 >= pages || !page.hasNextPage()) break;
                page = ex.getPage(page.getNextPage());
            }
            return out;
        });
    }
}
