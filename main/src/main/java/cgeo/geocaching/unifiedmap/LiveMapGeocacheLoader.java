package cgeo.geocaching.unifiedmap;

import cgeo.geocaching.connector.ConnectorFactory;
import cgeo.geocaching.enumerations.LoadFlags;
import cgeo.geocaching.enumerations.StatusCode;
import cgeo.geocaching.filters.core.GeocacheFilter;
import cgeo.geocaching.location.Viewport;
import cgeo.geocaching.models.Geocache;
import cgeo.geocaching.utils.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** accepts requests to load geocaches online but allows configuring of a maximum update rate */
public class LiveMapGeocacheLoader {

    private static final String LOGPRAEFIX = "LiveMapGeocacheLoader:";

    public static final long PROCESS_DELAY = 3000; // value is in milliseconds
    private static final long CACHE_EXPIRY = 10 * 60000; // value is in milliseconds

    private final Disposable actionDisposable;

    private long dirtyTime = -1;
    private Viewport viewport;
    private GeocacheFilter filter;

    public enum LoadState { REQUESTED, RUNNING, STOPPED }


    public static final class LiveDataState {
        public final LoadState loadState;
        public final Viewport cachedViewport;

        public final List<ConnectorState> connectors = new ArrayList<>();

        LiveDataState(final LoadState state, final Viewport cachedViewport) {
            this.loadState = state;
            this.cachedViewport = cachedViewport;
        }

        void addAll(final Collection<ConnectorState> connectorStates) {
            this.connectors.addAll(connectorStates);
        }
    }


    public static final class ConnectorState {
        public final String connectorName;
        public final StatusCode statusCode;
        public final boolean isPartial;
        public final int countOriginal;
        public final int countInDb;
        public final int countFiltered;
        public final long duration;
        public final String message;

        ConnectorState(final String connectorName, final StatusCode statusCode, final boolean isPartial,
                       final int countOriginal, final int countInDb, final int countFiltered, final long duration, final String message) {
            this.connectorName = connectorName;
            this.statusCode = statusCode;
            this.isPartial = isPartial;
            this.countOriginal = countOriginal;
            this.countInDb = countInDb;
            this.countFiltered = countFiltered;
            this.duration = duration;
            this.message = message;
        }

        public boolean isError() {
            return statusCode != StatusCode.NO_ERROR;
        }

        @NonNull
        @Override
        public String toString() {
            return "[" + connectorName + ":" + statusCode + ",p=" + isPartial + ", #:" + countOriginal + "," + countInDb + "," + countFiltered + ";" + duration + "ms;msg=" + message + "]";
        }

        @NonNull
        public String toUserDisplayableStringWithMarkup() {
            final String postfix = (message == null ? "" : " (" + message + ")") + (duration <= 0 ? "" : " (~" + Math.round(duration / 1000d) + "s)");
            final String prefix = "**" + connectorName + "**: ";
            if (isError()) {
                return prefix + statusCode + postfix;
            } else {
                return prefix + (countOriginal <= 0 ? "" : "#" + countOriginal + (countOriginal != countFiltered ? "->" + countFiltered : "")) + postfix;
            }
        }
    }

    private static final class Action implements Runnable {

        private final LiveMapGeocacheLoader loader;
        private final Consumer<LiveDataState> onStateChange;
        private final Consumer<Set<Geocache>> onResult;

        private long cachedResultTs;
        private GeocacheFilter cachedResultFilter;
        private Viewport cachedResultAvailableViewport;

        Action(final LiveMapGeocacheLoader loader, final Consumer<LiveDataState> onStateChange, final Consumer<Set<Geocache>> onResult) {
            this.onStateChange = onStateChange;
            this.onResult = onResult;
            this.loader = loader;
        }

        @Override
        public void run() {
            String logParams = "";
            try {
                //quick abort if nothing is requested
                if (this.loader.dirtyTime < 0) {
                    return;
                }

                final Viewport viewport;
                final GeocacheFilter filter;
                synchronized (loader) {
                    //check whether current request was already served with last request
                    viewport = this.loader.viewport;
                    filter = this.loader.filter;
                    logParams = "(vp=" + viewport + ",f=" + filter + ")";

                    //quick exit on invalid viewport
                    if (!Viewport.isValid(viewport)) {
                        Log.iForce(LOGPRAEFIX + "INVALID VIEWPORT " + logParams);
                        setState(LoadState.STOPPED);
                        this.loader.dirtyTime = -1;
                        return;
                    }

                    final boolean cacheIsValidForFilter = cachedResultAvailableViewport != null && GeocacheFilter.filtersSame(cachedResultFilter, filter) && (System.currentTimeMillis() - cachedResultTs) < CACHE_EXPIRY;

                    //quick exit on unconditional cache hit
                    if (cacheIsValidForFilter && cachedResultAvailableViewport.includes(viewport)) {
                        Log.iForce(LOGPRAEFIX + "CACHE HIT " + logParams);
                        setState(LoadState.STOPPED);
                        this.loader.dirtyTime = -1;
                        return;
                    }

                    //if request is real check if its time to really request
                    final long requestAge = System.currentTimeMillis() - this.loader.dirtyTime;
                    if (requestAge < PROCESS_DELAY) {
                        setState(LoadState.REQUESTED);
                        return;
                    }

                    //if we come here we HAVE to do an online request. Reset dirty state
                    this.loader.dirtyTime = -1;
                }

                Log.iForce(LOGPRAEFIX + "START" + logParams);
                setState(LoadState.RUNNING);


                final List<ConnectorState> stateData = new ArrayList<>();
                final Map<String, Viewport> cachedResultViewportsPerConnector = new HashMap<>();
                // DO ONLINE REQUEST retrieving live caches for x times the requested size (to fill up cache)
                final Viewport retrievalViewport = viewport.resize(3.0);
                final long startTs = System.currentTimeMillis();
                ConnectorFactory.searchByViewport(retrievalViewport, filter, (c, sr) -> {
                    final long duration = System.currentTimeMillis() - startTs;
                    //handle and send cache results for one connector
                    final int countOriginal = sr.getCount();
                    final Set<Geocache> result = sr.getCachesFromSearchResult(LoadFlags.LOAD_CACHE_OR_DB);
                    final int countInDb = result.size();
                    if (filter != null) {
                        filter.filterList(result);
                    }
                    final int countFiltered = result.size();
                    onResult.accept(result);

                    //collect caching information
                    final boolean isPartial = !sr.getPartialConnectors().isEmpty();
                    final StatusCode errorCode = sr.getError();
                    final Viewport connectorViewport;
                    if (errorCode != StatusCode.NO_ERROR) { // || isPartial && !connector supports centered-results!!!
                        connectorViewport = null;
                    } else if (isPartial) {
                       connectorViewport = Viewport.containing(result);
                    } else {
                        connectorViewport = retrievalViewport;
                    }
                    cachedResultViewportsPerConnector.put(c.getName(), connectorViewport);
                    final boolean isPartialForUser = connectorViewport == null || !connectorViewport.includes(viewport);
                    //set state data
                    stateData.add(new ConnectorState(c.getName(), errorCode, isPartialForUser, countOriginal, countInDb, countFiltered, duration, null));
                });

                //adjust result cache. cachedResultViewportOverall will be null on intersect if one connector failed
                this.cachedResultAvailableViewport = Viewport.intersect(cachedResultViewportsPerConnector.values());
                final boolean keepCache = this.cachedResultAvailableViewport != null;
                this.cachedResultFilter = keepCache ? filter : null;
                this.cachedResultTs = keepCache ? System.currentTimeMillis() : 0;

                //do status update
                setState(LoadState.STOPPED, stateData);

                Log.iForce(LOGPRAEFIX + "END  " + logParams + ": cachedViewport:" + this.cachedResultAvailableViewport + ", state: " + stateData);

            } catch (Exception e) {
                Log.e(LOGPRAEFIX + "UNEXPECTED ERROR" + logParams, e);
                setState(LoadState.STOPPED, Collections.singletonList(
                    new ConnectorState("Overall", StatusCode.UNKNOWN_ERROR, false, 0, 0, 0, -1, "Exception: " + e.getMessage())));
            }
            //if we have just done an online request, ensure that there's a grace period for the next
            synchronized (loader) {
                if (this.loader.dirtyTime > 0) {
                    this.loader.dirtyTime = System.currentTimeMillis();
                }
            }
        }

        private void setState(final LoadState newState) {
            setState(newState, null);
        }

        private void setState(final LoadState newState, final List<ConnectorState> connStates) {
            final LiveDataState ldState = new LiveDataState(newState, this.cachedResultAvailableViewport);
            if (connStates != null) {
                ldState.addAll(connStates);
            }
            Log.iForce(LOGPRAEFIX + "set state to " + ldState);
            onStateChange.accept(ldState);
        }
    }

    public LiveMapGeocacheLoader(final Consumer<LiveDataState> onStateChanged, final Consumer<Set<Geocache>> onResult) {
        this.actionDisposable = Schedulers.newThread().schedulePeriodicallyDirect(new Action(this, onStateChanged, onResult), 0, 250, TimeUnit.MILLISECONDS);
    }

    public synchronized void requestUpdate(final Viewport viewport, final GeocacheFilter filter, final boolean skipDelay) {
        this.viewport = viewport;
        this.filter = filter;
        final long timeMod = skipDelay ? PROCESS_DELAY : 0;
        this.dirtyTime = this.dirtyTime <= -1 ? System.currentTimeMillis() - timeMod : this.dirtyTime - timeMod;
    }

    public synchronized void cancelRequest() {
        this.dirtyTime = -1;
    }

    public void destroy() {
        this.actionDisposable.dispose();
    }
}
