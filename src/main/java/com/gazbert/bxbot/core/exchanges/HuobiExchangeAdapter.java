/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.core.exchanges;

import com.gazbert.bxbot.core.api.trading.BalanceInfo;
import com.gazbert.bxbot.core.api.trading.ExchangeTimeoutException;
import com.gazbert.bxbot.core.api.trading.MarketOrder;
import com.gazbert.bxbot.core.api.trading.MarketOrderBook;
import com.gazbert.bxbot.core.api.trading.OpenOrder;
import com.gazbert.bxbot.core.api.trading.OrderType;
import com.gazbert.bxbot.core.api.trading.TradingApi;
import com.gazbert.bxbot.core.api.trading.TradingApiException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * <p>
 * Exchange Adapter for integrating with the Huobi exchange.
 * The Huobi API is documented in English <a href="https://github.com/huobiapi/API_Docs_en/wiki">here</a>.
 * This adapter only supports Limit Orders using the
 * <a href="https://github.com/huobiapi/API_Docs_en/wiki/REST-Trade-API-Method">REST Trade API v3</a>.
 * </p>
 *
 * <p>
 * <strong>
 * DISCLAIMER:
 * This Exchange Adapter is provided as-is; it might have bugs in it and you could lose money. Despite running live
 * on Huobi, it has only been unit tested up until the point of calling the
 * {@link #sendPublicRequestToExchange(String)} and {@link #sendAuthenticatedRequestToExchange(String, String, Map)}
 * methods. Use it at our own risk!
 * </strong>
 * </p>
 *
 * <p>
 * This adapter only supports trading BTC, i.e. BTC-CNY and BTC-USD markets. It does not support trading of LTC-CNY.
 * </p>
 *
 * <p>
 * The public exchange calls {@link #getMarketOrders(String)} and {@link #getLatestMarketPrice(String)} expect market id
 * values of 'BTC-CNY' or 'BTC-USD' only. See Huobi
 * <a href="https://github.com/huobiapi/API_Docs_en/wiki/REST-Candlestick-Chart">ticker</a> and
 * <a href="https://github.com/huobiapi/API_Docs_en/wiki/REST-Order-Book-and-TAS">detail</a> API docs.
 * </p>
 *
 * <p>
 * The private authenticated calls {@link #getYourOpenOrders(String)},
 * {@link #getBalanceInfo()}, {@link #createOrder(String, OrderType, BigDecimal, BigDecimal)}, and
 * {@link #cancelOrder(String, String)} expect the market id to be 'usd' or 'cny' only. See example
 * <a href="https://github.com/huobiapi/API_Docs_en/wiki/REST-get_orders">get_orders</a> API doc for spec. The
 * {@link #getAuthenticatedMarketIdForGivenPublicMarketId(String)} util methods maps the TradingAPI methods' marketId
 * args to the corresponding authenticated request marketId.
 * </p>
 *
 * <p>
 * The private authenticated call {@link #getBalanceInfo()} uses the 'account-info-market' property value defined in the
 * huobi-config.properties file when it fetches wallet balance info from the exchange.
 * Supported values are 'usd' and 'cny'. The 'account-info-market' property value <em>must</em> be set to
 * the corresponding value that you expect your private authenticated calls {@link #getYourOpenOrders(String)},
 * {@link #createOrder(String, OrderType, BigDecimal, BigDecimal)}, and {@link #cancelOrder(String, String)} to use.
 * For example, if the {@link TradingApi} calls pass in 'BTC-USD' as the marketId, then the huobi-config.properties
 * 'account-info-market' property must be set to 'usd'.
 * </p>
 *
 * <p>
 * The exchange % buy and sell fees are currently loaded statically from the huobi-config.properties file on startup;
 * they are not fetched from the exchange at runtime as the Huobi API does not support this - it only provides the fee
 * monetary value for a given order id via the order_info API call. The fees are used across all markets.
 * Make sure you keep an eye on the <a href="https://www.huobi.com/about/detail">exchange fees</a> and update the
 * config accordingly. This adapter will use the <em>Taker</em> fees to keep things simple for now.
 * </p>
 *
 * <p>
 * NOTE: Huobi requires all price values to be limited to 2 decimal places and amount values to be limited to 4 decimal
 * places when creating orders. This adapter truncates any prices with more than 2 decimal places and rounds using
 * {@link java.math.RoundingMode#HALF_EVEN}, E.g. 250.176 would be sent to the exchange as 250.18. The same is done for
 * the order amount, but to 4 decimal places.
 * </p
 *
 * <p>
 * The Exchange Adapter is <em>not</em> thread safe. It expects to be called using a single thread in order to
 * preserve trade execution order. The {@link URLConnection} achieves this by blocking/waiting on the input stream
 * (response) for each API call.
 * </p>
 *
 * <p>
 * The {@link TradingApi} calls will throw a {@link ExchangeTimeoutException} if a network error occurs trying to
 * connect to the exchange. A {@link TradingApiException} is thrown for <em>all</em> other failures.
 * </p>
 *
 * @author gazbert
 */
public final class HuobiExchangeAdapter implements TradingApi {

    private static final Logger LOG = Logger.getLogger(HuobiExchangeAdapter.class);

    /**
     * The version of the Huobi API being used.
     */
    private static final String HUOBI_API_VERSION = "v3";

    /**
     * The public API URI.
     */
    private static final String PUBLIC_API_BASE_URL = "http://api.huobi.com/";

    /**
     * The Authenticated API URI.
     */
    private static final String AUTHENTICATED_API_URL = "https://api.huobi.com/api" + HUOBI_API_VERSION + "/";

    /**
     * Used for reporting unexpected errors.
     */
    private static final String UNEXPECTED_ERROR_MSG = "Unexpected error has occurred in Huobi Exchange Adapter. ";

    /**
     * Unexpected IO error message for logging.
     */
    private static final String UNEXPECTED_IO_ERROR_MSG = "Failed to connect to Exchange due to unexpected IO error.";

    /**
     * IO 50x Timeout error message for logging.
     */
    private static final String IO_50X_TIMEOUT_ERROR_MSG = "Failed to connect to Exchange due to 50x timeout.";

    /**
     * IO Socket Timeout error message for logging.
     */
    private static final String IO_SOCKET_TIMEOUT_ERROR_MSG = "Failed to connect to Exchange due to socket timeout.";

    /**
     * Used for building error messages for missing config.
     */
    private static final String CONFIG_IS_NULL_OR_ZERO_LENGTH = " cannot be null or zero length! HINT: is the value set in the ";

    /**
     * Your Huobi API keys and connection timeout config.
     * This file must be on BX-bot's runtime classpath located at: ./resources/huobi/huobi-config.properties
     */
    private static final String CONFIG_FILE = "huobi/huobi-config.properties";

    /**
     * Name of PUBLIC key prop in config file.
     */
    private static final String KEY_PROPERTY_NAME = "key";

    /**
     * Name of secret prop in config file.
     */
    private static final String SECRET_PROPERTY_NAME = "secret";

    /**
     * Name of buy fee property in config file.
     */
    private static final String BUY_FEE_PROPERTY_NAME = "buy-fee";

    /**
     * Name of sell fee property in config file.
     */
    private static final String SELL_FEE_PROPERTY_NAME = "sell-fee";

    /**
     * Name of connection timeout property in config file.
     */
    private static final String CONNECTION_TIMEOUT_PROPERTY_NAME = "connection-timeout";

    /**
     * Name of account info market property in config file.
     */
    private static final String ACCOUNT_INFO_MARKET_PROPERTY_NAME = "account-info-market";

    /**
     * The connection timeout in SECONDS for terminating hung connections to the exchange.
     */
    private int connectionTimeout;

    /**
     * The market (currency) used for fetching wallet balance info using the exchange get_account_info API call.
     * See: https://github.com/huobiapi/API_Docs_en/wiki/REST-get_account_info
     */
    private String accountInfoMarket;

    /**
     * Exchange buy fees in % in {@link BigDecimal} format.
     */
    private BigDecimal buyFeePercentage;

    /**
     * Exchange sell fees in % in {@link BigDecimal} format.
     */
    private BigDecimal sellFeePercentage;

    /**
     * Used to indicate if we have initialised the secure messaging layer.
     */
    private boolean initializedSecureMessagingLayer = false;

    /**
     * The key used in the secure message.
     */
    private String key = "";

    /**
     * The secret used for signing secure message.
     */
    private String secret = "";

    /**
     * The Message Digest generator used by the secure messaging layer.
     * Used to create the hash of the entire message with the private key to ensure message integrity.
     */
    private MessageDigest messageDigest;

    /**
     * GSON engine used for parsing JSON in Huobi API call responses.
     */
    private Gson gson;

    /**
     * Supported markets used in the public exchange calls.
     */
    private enum PublicExchangeCallMarket {

        BTC_USD("BTC-USD"), BTC_CNY("BTC-CNY");

        private String market;

        PublicExchangeCallMarket(String market) {
            this.market = market;
        }

        public String getStringValue() {
            return market;
        }
    }

    /**
     * Supported 'markets' [fiat currencies] used in the authenticated exchange calls.
     */
    private enum AuthenticatedExchangeCallMarket {

        USD("usd"), CNY("cny");
        private String market;


        AuthenticatedExchangeCallMarket(String market) {
            this.market = market;
        }

        public String getStringValue() {
            return market;
        }
    }


    /**
     * Constructor initialises the Exchange Adapter for using the Huobi API.
     */
    public HuobiExchangeAdapter() {
        loadConfig();
        initSecureMessageLayer();
        initGson();
    }

    // ------------------------------------------------------------------------------------------------
    // Huobi REST Trade API Calls adapted to the Trading API.
    // See https://github.com/huobiapi/API_Docs_en/wiki/REST-Trade-API-Method
    // ------------------------------------------------------------------------------------------------

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws
            TradingApiException, ExchangeTimeoutException {

        try {
            final String marketIdForAuthenticatedRequest = getAuthenticatedMarketIdForGivenPublicMarketId(marketId);

            final Map<String, String> params = getRequestParamMap();
            params.put("coin_type", "1"); // "1" = BTC

            // we need to limit amount to 2 decimal places else exchange will barf
            params.put("price", new DecimalFormat("#.##").format(price));

            // we need to limit amount to 4 decimal places else exchange will barf
            params.put("amount", new DecimalFormat("#.####").format(quantity));

            String apiCall;
            if (orderType == OrderType.BUY) {
                apiCall = "buy";  // buy limit order
            } else if (orderType == OrderType.SELL) {
                apiCall = "sell"; // sell limit order
            } else {
                final String errorMsg = "Invalid order type: '" + orderType + "' - Can only be " +
                        Arrays.toString(OrderType.values());
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            final String response = sendAuthenticatedRequestToExchange(apiCall, marketIdForAuthenticatedRequest, params);
            if (LOG.isDebugEnabled()) {
                LOG.debug("createOrder() response: " + response);
            }

            final HuobiOrderResponse createOrderResponse = gson.fromJson(response, HuobiOrderResponse.class);
            if (createOrderResponse.result != null && createOrderResponse.result.equalsIgnoreCase("success")) {
                return Long.toString(createOrderResponse.id);
            } else {
                final String errorMsg = "Failed to place order on exchange. Error response: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public boolean cancelOrder(String orderId, String marketId) throws TradingApiException, ExchangeTimeoutException {

        try {
            final String marketIdForAuthenticatedRequest = getAuthenticatedMarketIdForGivenPublicMarketId(marketId);

            final Map<String, String> params = getRequestParamMap();
            params.put("coin_type", "1"); // "1" = BTC
            params.put("id", orderId);

            final String response = sendAuthenticatedRequestToExchange("cancel_order", marketIdForAuthenticatedRequest,
                    params);

            if (LOG.isDebugEnabled()) {
                LOG.debug("cancelOrder() response: " + response);
            }

            final HuobiCancelOrderResponse cancelOrderResponse = gson.fromJson(response, HuobiCancelOrderResponse.class);
            if (cancelOrderResponse.result != null && cancelOrderResponse.result.equalsIgnoreCase("success")) {
                return true;
            } else {
                final String errorMsg = "Failed to cancel order on exchange. Error response: " + response;
                LOG.error(errorMsg);
                return false;
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public List<OpenOrder> getYourOpenOrders(String marketId) throws TradingApiException, ExchangeTimeoutException {

        try {
            final String marketIdForAuthenticatedRequest = getAuthenticatedMarketIdForGivenPublicMarketId(marketId);

            final Map<String, String> params = getRequestParamMap();
            params.put("coin_type", "1"); // "1" = BTC

            final String response = sendAuthenticatedRequestToExchange("get_orders", marketIdForAuthenticatedRequest,
                    params);

            if (LOG.isDebugEnabled()) {
                LOG.debug("getYourOpenOrders() response: " + response);
            }

            final HuobiOpenOrderResponseWrapper huobiOpenOrdersWrapper
                    = gson.fromJson(response, HuobiOpenOrderResponseWrapper.class);

            if (huobiOpenOrdersWrapper.code == 0) {

                // adapt
                final List<OpenOrder> ordersToReturn = new ArrayList<>();
                for (final HuobiOpenOrder openOrder : huobiOpenOrdersWrapper.openOrders) {
                    OrderType orderType;
                    switch (openOrder.type) {
                        case 1:
                            orderType = OrderType.BUY;
                            break;
                        case 2:
                            orderType = OrderType.SELL;
                            break;
                        default:
                            throw new TradingApiException(
                                    "Unrecognised order type received in getYourOpenOrders(). Value: " + openOrder.type);
                    }

                    final OpenOrder order = new OpenOrder(
                            Long.toString(openOrder.id),
                            new Date(openOrder.order_time),
                            marketId,
                            orderType,
                            openOrder.order_price,
                            openOrder.order_amount.subtract(openOrder.processed_amount), // remaining
                            openOrder.order_amount,
                            openOrder.order_price.multiply(openOrder.order_amount) // total is not provided by Huobi
                    );

                    ordersToReturn.add(order);
                }
                return ordersToReturn;

            } else {
                final String errorMsg = "Failed to get Open Order Info from exchange. Error response: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public MarketOrderBook getMarketOrders(String marketId) throws TradingApiException, ExchangeTimeoutException {

        try {

            // Yuck!
            final String apiCall;
            if (marketId.equals(PublicExchangeCallMarket.BTC_USD.getStringValue())) {
                apiCall = "usdmarket/detail_btc_json.js";
            } else if (marketId.equals(PublicExchangeCallMarket.BTC_CNY.getStringValue())) {
                apiCall = "staticmarket/detail_btc_json.js";
            } else {
                final String errorMsg = "Unrecognised marketId to fetch market orders for: '" +
                        marketId + "'. Supported markets are: " + Arrays.toString(PublicExchangeCallMarket.values());
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            final String response = sendPublicRequestToExchange(apiCall);

            if (LOG.isDebugEnabled()) {
                LOG.debug("getMarketOrders() response: " + response);
            }

            final HuobiOrderBookWrapper orderBook = gson.fromJson(response, HuobiOrderBookWrapper.class);

            // adapt BUYs
            final List<MarketOrder> buyOrders = new ArrayList<>();
            for (HuobiMarketOrder okCoinBuyOrder : orderBook.buys) {
                final MarketOrder buyOrder = new MarketOrder(
                        OrderType.BUY,
                        okCoinBuyOrder.price,
                        okCoinBuyOrder.amount,
                        okCoinBuyOrder.price.multiply(okCoinBuyOrder.amount));
                buyOrders.add(buyOrder);
            }

            // adapt SELLs
            final List<MarketOrder> sellOrders = new ArrayList<>();
            for (HuobiMarketOrder okCoinSellOrder : orderBook.sells) {
                final MarketOrder sellOrder = new MarketOrder(
                        OrderType.SELL,
                        okCoinSellOrder.price,
                        okCoinSellOrder.amount,
                        okCoinSellOrder.price.multiply(okCoinSellOrder.amount));
                sellOrders.add(sellOrder);
            }

            return new MarketOrderBook(marketId, sellOrders, buyOrders);

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BalanceInfo getBalanceInfo() throws TradingApiException, ExchangeTimeoutException {

        try {

            final String response = sendAuthenticatedRequestToExchange("get_account_info", accountInfoMarket, null);

            if (LOG.isDebugEnabled()) {
                LOG.debug("getBalanceInfo() response: " + response);
            }

            final HuobiAccountInfo huobiAccountInfo = gson.fromJson(response, HuobiAccountInfo.class);
            if (huobiAccountInfo.code == 0) {

                // adapt
                final Map<String, BigDecimal> balancesAvailable = new HashMap<>();
                balancesAvailable.put("BTC", huobiAccountInfo.available_btc_display);
                balancesAvailable.put("CNY", huobiAccountInfo.available_cny_display);
                balancesAvailable.put("USD", huobiAccountInfo.available_usd_display);

                final Map<String, BigDecimal> balancesOnOrder = new HashMap<>();
                balancesOnOrder.put("BTC", huobiAccountInfo.frozen_btc_display);
                balancesOnOrder.put("CNY", huobiAccountInfo.frozen_cny_display);
                balancesOnOrder.put("USD", huobiAccountInfo.frozen_usd_display);

                return new BalanceInfo(balancesAvailable, balancesOnOrder);

            } else {
                final String errorMsg = "Failed to get Balance Info from exchange. Error response: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws ExchangeTimeoutException, TradingApiException {

        try {

            // Beurgh!
            final String apiCall;
            if (marketId.equals(PublicExchangeCallMarket.BTC_USD.getStringValue())) {
                apiCall = "usdmarket/ticker_btc_json.js";
            } else if (marketId.equals(PublicExchangeCallMarket.BTC_CNY.getStringValue())) {
                apiCall = "staticmarket/ticker_btc_json.js";
            } else {
                final String errorMsg = "Unrecognised marketId to fetch latest market price for: '" +
                        marketId + "'. Supported markets are: " + Arrays.toString(PublicExchangeCallMarket.values());
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            final String response = sendPublicRequestToExchange(apiCall);

            if (LOG.isDebugEnabled()) {
                LOG.debug("getLatestMarketPrice() response: " + response);
            }

            final HuobiTickerWrapper tickerWrapper = gson.fromJson(response, HuobiTickerWrapper.class);
            return tickerWrapper.ticker.last;

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BigDecimal getPercentageOfBuyOrderTakenForExchangeFee(String marketId) throws TradingApiException,
            ExchangeTimeoutException {

        // Huobi does not provide API call for fetching % buy fee; it only provides the fee monetary value for a
        // given order via order_info API call. We load the % fee statically from huobi-config.properties
        return buyFeePercentage;
    }

    @Override
    public BigDecimal getPercentageOfSellOrderTakenForExchangeFee(String marketId) throws TradingApiException,
            ExchangeTimeoutException {

        // Huobi does not provide API call for fetching % sell fee; it only provides the fee monetary value for a
        // given order via order_info API call. We load the % fee statically from huobi-config.properties
        return sellFeePercentage;
    }

    @Override
    public String getImplName() {
        return "Huobi REST Trade API v3";
    }

    // ------------------------------------------------------------------------------------------------
    //  GSON classes for JSON responses.
    //  See https://github.com/huobiapi/API_Docs_en/wiki/REST-Trade-API-Method
    // ------------------------------------------------------------------------------------------------

    /**
     * GSON class for 'cancel_order' API call responses.
     *
     * @author gazbert
     */
    private static class HuobiCancelOrderResponse extends HuobiMessageBase {

        public String result;

        @Override
        public String toString() {
            return HuobiCancelOrderResponse.class.getSimpleName()
                    + " ["
                    + "result=" + result
                    + "]";
        }
    }

    /**
     * GSON class for 'sell' and 'buy' limit order API call responses.
     *
     * @author gazbert
     */
    private static class HuobiOrderResponse extends HuobiMessageBase {

        public String result;
        public long id;

        @Override
        public String toString() {
            return HuobiOrderResponse.class.getSimpleName()
                    + " ["
                    + "result=" + result
                    + ", id=" + id
                    + "]";
        }
    }

    /**
     * GSON class for get_orders API call response.
     *
     * @author gazbert
     */
    private static class HuobiOpenOrderResponseWrapper extends HuobiMessageBase {

        public HuobiOpenOrder[] openOrders;

        @Override
        public String toString() {
            return HuobiOpenOrderResponseWrapper.class.getSimpleName()
                    + " ["
                    + "openOrders=" + Arrays.toString(openOrders)
                    + "]";
        }
    }

    /**
     * GSON class for get_orders API call response.
     *
     * @author gazbert
     */
    private static class HuobiOpenOrder {

        public long id;
        public int type; // 1=buy 2=sell
        public BigDecimal order_price;
        public BigDecimal order_amount;
        public BigDecimal processed_amount;
        public long order_time;

        @Override
        public String toString() {
            return HuobiOpenOrder.class.getSimpleName()
                    + " ["
                    + "id=" + id
                    + ", type=" + type
                    + ", order_price=" + order_price
                    + ", order_amount=" + order_amount
                    + ", processed_amount=" + processed_amount
                    + ", order_time=" + order_time
                    + "]";
        }
    }

    /**
     * GSON class for REST Order Book (detail_btc_json.js) API call response.
     *
     * This one is a bit crazy...
     *
     * @author gazbert
     */
    private static class HuobiOrderBookWrapper {

        public BigDecimal total;
        public BigDecimal p_high;
        public BigDecimal p_open;
        public BigDecimal p_new;
        public BigDecimal p_low;
        public List<HuobiTopMarketOrder> top_buy;
        public List<HuobiMarketOrder> buys;
        public List<HuobiTopMarketOrder> top_sell;
        public BigDecimal amount;
        public BigDecimal level;
        public List<HuobiMarketOrder> sells;
        public List<HuobiTrade> trades;
        public BigDecimal amp;
        public BigDecimal p_last;

        @Override
        public String toString() {
            return HuobiOrderBookWrapper.class.getSimpleName()
                    + " ["
                    + "total=" + total
                    + ", p_high=" + p_high
                    + ", p_open=" + p_open
                    + ", p_new=" + p_new
                    + ", p_low=" + p_low
                    + ", top_buy=" + top_buy
                    + ", buys=" + buys
                    + ", top_sell=" + top_sell
                    + ", amount=" + amount
                    + ", level=" + level
                    + ", sells=" + sells
                    + ", trades=" + trades
                    + ", amp=" + amp
                    + ", p_last=" + p_last
                    + "]";
        }
    }

    /**
     * GSON class for holding Huobi Market Order.
     *
     * @author gazbert
     */
    private static class HuobiMarketOrder {

        public BigDecimal amount;
        public BigDecimal level;
        public BigDecimal price;

        @Override
        public String toString() {
            return HuobiMarketOrder.class.getSimpleName()
                    + " ["
                    + "amount=" + amount
                    + ", level=" + level
                    + ", price=" + price
                    + "]";
        }
    }

    /**
     * GSON class for holding Huobi top Market Order.
     *
     * @author gazbert
     */
    private static class HuobiTopMarketOrder extends HuobiMarketOrder {

        public BigDecimal accu;

        @Override
        public String toString() {
            return HuobiTopMarketOrder.class.getSimpleName()
                    + " ["
                    + "accu=" + accu
                    + "]";
        }
    }

    /**
     * GSON class for holding Huobi Trade.
     *
     * @author gazbert
     */
    private static class HuobiTrade {

        public BigDecimal amount;
        public String time;
        public BigDecimal price;
        public String en_type; // e.g 'bid'
        public String type; // e.g. "买入"

        @Override
        public String toString() {
            return HuobiTrade.class.getSimpleName()
                    + " ["
                    + "amount=" + amount
                    + ", time=" + time
                    + ", price=" + price
                    + ", en_type=" + en_type
                    + ", type=" + type
                    + "]";
        }
    }

    /**
     * GSON class for REST candlestick (ticker_btc_json.js) API call response.
     *
     * @author gazbert
     */
    private static class HuobiTickerWrapper {

        public long time;
        public HuobiTicker ticker;

        @Override
        public String toString() {
            return HuobiTickerWrapper.class.getSimpleName()
                    + " ["
                    + "time=" + time
                    + ", ticker=" + ticker
                    + "]";
        }
    }

    /**
     * GSON class for Huobi ticker returned within REST candlestick (ticker_btc_json.js) API call response.
     *
     * @author gazbert
     */
    private static class HuobiTicker {

        public BigDecimal vol;
        public BigDecimal last;
        public BigDecimal buy;
        public BigDecimal sell;
        public BigDecimal high;
        public BigDecimal low;

        @Override
        public String toString() {
            return HuobiTicker.class.getSimpleName()
                    + " ["
                    + "vol=" + vol
                    + ", last=" + last
                    + ", buy=" + buy
                    + ", sell=" + sell
                    + ", high=" + high
                    + ", low=" + low
                    + "]";
        }
    }

    /**
     * GSON class for get_account_info API call response.
     *
     * @author gazbert
     */
    private static class HuobiAccountInfo extends HuobiMessageBase {

        public BigDecimal total;
        public BigDecimal net_asset;
        public BigDecimal available_cny_display;
        public BigDecimal available_btc_display;
        public BigDecimal available_usd_display;
        public BigDecimal frozen_cny_display;
        public BigDecimal frozen_btc_display;
        public BigDecimal frozen_usd_display;
        public BigDecimal loan_cny_display;
        public BigDecimal loan_btc_display;
        public BigDecimal loan_usd_display;

        @Override
        public String toString() {
            return HuobiAccountInfo.class.getSimpleName()
                    + " ["
                    + "total=" + total
                    + ", net_asset=" + net_asset
                    + ", available_cny_display=" + available_cny_display
                    + ", available_btc_display=" + available_btc_display
                    + ", available_usd_display=" + available_usd_display
                    + ", frozen_cny_display=" + frozen_cny_display
                    + ", frozen_btc_display=" + frozen_btc_display
                    + ", frozen_usd_display=" + frozen_usd_display
                    + ", loan_cny_display=" + loan_cny_display
                    + ", loan_btc_display=" + loan_btc_display
                    + ", loan_usd_display=" + loan_usd_display
                    + "]";
        }
    }

    /**
     * GSON base class for API call requests and responses.
     *
     * @author gazbert
     */
    private static class HuobiMessageBase {

        public int code; // see https://github.com/huobiapi/API_Docs_en/wiki/REST-Error-Code
        public String message;
        public String msg; // for backwards compatibility

        @Override
        public String toString() {
            return HuobiMessageBase.class.getSimpleName()
                    + " ["
                    + "code=" + code
                    + ", message=" + message
                    + ", msg=" + msg
                    + "]";
        }
    }

    /**
     * Custom GSON deserializer required as exchange returns JSON object for failed fetches of open orders,
     * but an array of open orders for successful fetches... :-/
     *
     * <p>
     * Error response:
     * <pre>
     * {
     *    code": 78,
     *    "msg": "无效的交易市场",
     *    "message": "无效的交易市场"
     * }
     * </pre>
     * </p>
     *
     * <p>
     * Success response:
     * <pre>
     * [
     *    {
     *       "id": 37433151,
     *       "type": 2,
     *       "order_price": "270.18",
     *       "order_amount": "0.0100",
     *       "processed_amount": "0.0010",
     *       "order_time": 1444334637
     *    },
     *    {
     *       "id": 37432968,
     *       "type": 2,
     *       "order_price": "260.18",
     *       "order_amount": "0.0100",
     *       "processed_amount": "0.0000",
     *       "order_time": 1444334609
     *    }
     * ]
     * </pre>
     * </p>
     *
     * @author gazbert
     *
     */
    private class GetHuobiOpenOrdersDeserializer implements JsonDeserializer<HuobiOpenOrderResponseWrapper> {

        public HuobiOpenOrderResponseWrapper deserialize(JsonElement json, Type type, JsonDeserializationContext context)
                throws JsonParseException {

            final HuobiOpenOrderResponseWrapper huobiOpenOrderResponseWrapper = new HuobiOpenOrderResponseWrapper();
            if (json.isJsonObject()) {

                // means we have an error response object
                final JsonObject jsonObject = json.getAsJsonObject();
                for (Map.Entry<String, JsonElement> jsonOrder : jsonObject.entrySet()) {

                    final String key = jsonOrder.getKey();
                    switch(key) {
                        case "code" :
                            huobiOpenOrderResponseWrapper.code = context.deserialize(jsonOrder.getValue(), Integer.class);
                            break;
                        case "message" :
                            huobiOpenOrderResponseWrapper.message = context.deserialize(jsonOrder.getValue(), String.class);
                            break;
                        case "msg" :
                            huobiOpenOrderResponseWrapper.msg =  context.deserialize(jsonOrder.getValue(), String.class);
                            break;
                        default:
                            throw new IllegalArgumentException("Failed to unmarshal getYourOpenOrder Response from exchange. " +
                                    "Unrecognised field found: " + key + " Payload:" + jsonObject);
                    }
                }

            } else {

                // assume we have an array of open orders ;-o
                huobiOpenOrderResponseWrapper.openOrders = gson.fromJson(json, HuobiOpenOrder[].class);
            }
            return huobiOpenOrderResponseWrapper;
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Transport layer methods
    // ------------------------------------------------------------------------------------------------

    /**
     * <p>
     * Makes a public API call to Huobi exchange. Uses HTTP GET.
     * </p>
     *
     * @param apiMethod the API method to call.
     * @return the response from the exchange.
     * @throws ExchangeTimeoutException if there is a network issue connecting to exchange.
     * @throws TradingApiException      if anything unexpected happens.
     */
    private String sendPublicRequestToExchange(String apiMethod) throws ExchangeTimeoutException, TradingApiException {

        HttpURLConnection exchangeConnection = null;
        final StringBuilder exchangeResponse = new StringBuilder();

        try {

            final URL url = new URL(PUBLIC_API_BASE_URL + apiMethod);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using following URL for API call: " + url);
            }

            exchangeConnection = (HttpURLConnection) url.openConnection();
            exchangeConnection.setUseCaches(false);
            exchangeConnection.setDoOutput(true);

            exchangeConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            // Er, perhaps, I need to be a bit more stealth here...
            exchangeConnection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.114 Safari/537.36");

            /*
             * Add a timeout so we don't get blocked indefinitley; timeout on URLConnection is in millis.
             * connectionTimeout is in SECONDS and comes from huobi-config.properties config.
             */
            final int timeoutInMillis = connectionTimeout * 1000;
            exchangeConnection.setConnectTimeout(timeoutInMillis);
            exchangeConnection.setReadTimeout(timeoutInMillis);

            // Grab the response - we just block here as per Connection API
            final BufferedReader responseInputStream = new BufferedReader(new InputStreamReader(
                    exchangeConnection.getInputStream()));

            // Read the JSON response lines into our response buffer
            String responseLine;
            while ((responseLine = responseInputStream.readLine()) != null) {
                exchangeResponse.append(responseLine);
            }
            responseInputStream.close();

            // return the JSON response string
            return exchangeResponse.toString();

        } catch (MalformedURLException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);

        } catch (SocketTimeoutException e) {
            final String errorMsg = IO_SOCKET_TIMEOUT_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new ExchangeTimeoutException(errorMsg, e);

        } catch (IOException e) {

            try {

                /*
                 * This stuff happens a lot on Huobi :-/
                 */
                if (e.getMessage() != null &&
                        (e.getMessage().contains("Connection reset") ||
                         e.getMessage().contains("Remote host closed connection during handshake") ||
                         e.getMessage().contains("Unexpected end of file from server") ||
                         e.getMessage().contains("Connection refused"))) {

                    final String errorMsg = "Failed to connect to Huobi. SSL Connection was reset by the server.";
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                /*
                 * Exchange sometimes fails with these codes, but recovers by next request...
                 */
                } else if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 502
                        || exchangeConnection.getResponseCode() == 503
                        || exchangeConnection.getResponseCode() == 504)) {

                    final String errorMsg = IO_50X_TIMEOUT_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                } else {
                    final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    throw new TradingApiException(errorMsg, e);
                }
            } catch (IOException e1) {

                final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                LOG.error(errorMsg, e1);
                throw new TradingApiException(errorMsg, e1);
            }
        } finally {
            if (exchangeConnection != null) {
                exchangeConnection.disconnect();
            }
        }
    }

    /**
     * <p>
     * Makes Authenticated API call to Huobi exchange. Uses HTTP POST.
     * </p>
     *
     * <p>
     * Authentication process is documented
     * <a href="https://github.com/huobiapi/API_Docs_en/wiki/REST-Trade-API-Method">here</a>.
     * </p>
     *
     * <pre>
     * MD5 signatures must be lowercase.
     *
     * Use UTF-8 encoding When you sign. After MD5 hashing, use two hexadecimal lowercase letters for each byte,
     * from high to low. For example:
     *
     * md5("Hello, Bitcoin") = d6b6e11652b0c93c4f14cfb84c380541
     * md5("ABC123abc") = 85716f0702d2d464803e1366a7678d0b
     *
     * method       required   Request method get_account_info
     * access_key   required   Access to the public key
     * created      required   Request time, unix timestamp in second, length is 10
     * sign         required   MD5 Signature result from: md5(access_key=xxxxxxxx-xxxxxxxx-xxxxxxxx-xxxxxxxx&created=1386844119&method=get_account_info&secret_key=xxxxxxxx-xxxxxxxx-xxxxxxxx-xxxxxxxx)
     * market       optional   Not participate in the sign signature process, the transaction market(cny:RMB market，usd:USD market，the default is cny)
     * </pre>
     *
     * @param apiMethod the API method to call.
     * @param marketId the (optional) market id to use in the API method call.
     * @param params    the query param args to use in the API call.
     * @return the response from the exchange.
     * @throws ExchangeTimeoutException if there is a network issue connecting to exchange.
     * @throws TradingApiException      if anything unexpected happens.
     */
    @SuppressWarnings("deprecation")
    private String sendAuthenticatedRequestToExchange(String apiMethod, String marketId, Map<String, String> params)
            throws ExchangeTimeoutException, TradingApiException {

        if (!initializedSecureMessagingLayer) {
            final String errorMsg = "Message security layer has not been initialized.";
            LOG.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        HttpURLConnection exchangeConnection = null;
        final StringBuilder exchangeResponse = new StringBuilder();

        try {
            if (params == null) {
                params = new HashMap<>();
            }

            final Map<String, String> signatureParams = new HashMap<>(params);
            signatureParams.put("method", apiMethod);
            signatureParams.put("access_key", key);
            signatureParams.put("created", Long.toString(System.currentTimeMillis() / 1000)); // unix time in secs
            signatureParams.put("secret_key", secret);

            final String sortedQueryString = createAlphabeticallySortedQueryString(signatureParams);

            final String signature = createMd5HashAndReturnAsLowerCaseString(sortedQueryString);
            signatureParams.put("sign", signature);

            // IMPORTANT - remove secret key from params after creating signature.
            signatureParams.remove("secret_key");

            final StringBuilder payloadBuilder = new StringBuilder();
            payloadBuilder.append("method=").append(URLEncoder.encode(signatureParams.get("method"))).append("&");
            payloadBuilder.append("access_key=").append(URLEncoder.encode(signatureParams.get("access_key"))).append("&");
            payloadBuilder.append("created=").append(URLEncoder.encode(signatureParams.get("created"))).append("&");
            payloadBuilder.append("sign=").append(URLEncoder.encode(signatureParams.get("sign")));

            /*
             * Market id is 'optional' as per API docs for:
             *
             * https://github.com/huobiapi/API_Docs_en/wiki/REST-buy
             * https://github.com/huobiapi/API_Docs_en/wiki/REST-sell
             * https://github.com/huobiapi/API_Docs_en/wiki/REST-get_orders
             * https://github.com/huobiapi/API_Docs_en/wiki/REST-get_account_info
             *
             * Not really! If you use another market other than 'cny', you MUST specify market param else exchange
             * assumes your request is for 'cny' market... and very strange error codes are returned... ;-/
             */
            if (marketId != null && !marketId.isEmpty()) {
                payloadBuilder.append("&market=").append(marketId);
            }

            // add the caller's query params
            for (final Map.Entry<String, String> queryParam : params.entrySet()) {
                payloadBuilder.append("&").append(queryParam.getKey()).append("=").append(
                        URLEncoder.encode(queryParam.getValue()));
            }

            final URL url = new URL(AUTHENTICATED_API_URL);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using following URL for API call: " + url);
            }

            exchangeConnection = (HttpURLConnection) url.openConnection();
            exchangeConnection.setUseCaches(false);
            exchangeConnection.setDoOutput(true);

            exchangeConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            // Er, perhaps, I need to be a bit more stealth here...
            exchangeConnection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.114 Safari/537.36");

            /*
             * Add a timeout so we don't get blocked indefinitley; timeout on URLConnection is in millis.
             * connectionTimeout is in SECONDS and comes from huobi-config.properties config.
             */
            final int timeoutInMillis = connectionTimeout * 1000;
            exchangeConnection.setConnectTimeout(timeoutInMillis);
            exchangeConnection.setReadTimeout(timeoutInMillis);

            // POST the request
            final OutputStreamWriter outputPostStream = new OutputStreamWriter(exchangeConnection.getOutputStream());

            // WARNING: careful when you log this - it outputs your access_key
//            if (LOG.isDebugEnabled()) {
//                LOG.debug("Wire payload: " + payloadBuilder.toString());
//            }

            outputPostStream.write(payloadBuilder.toString());
            outputPostStream.close();

            // Grab the response - we just block here as per Connection API
            final BufferedReader responseInputStream = new BufferedReader(new InputStreamReader(
                    exchangeConnection.getInputStream()));

            // Read the JSON response lines into our response buffer
            String responseLine;
            while ((responseLine = responseInputStream.readLine()) != null) {
                exchangeResponse.append(responseLine);
            }
            responseInputStream.close();

            // return the JSON response string
            return exchangeResponse.toString();

        } catch (MalformedURLException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);

        } catch (SocketTimeoutException e) {
            final String errorMsg = IO_SOCKET_TIMEOUT_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new ExchangeTimeoutException(errorMsg, e);

        } catch (IOException e) {

            try {

                /*
                 * This stuff happens a lot on Huobi :-/
                 */
                if (e.getMessage() != null &&
                        (e.getMessage().contains("Connection reset") ||
                         e.getMessage().contains("Remote host closed connection during handshake") ||
                         e.getMessage().contains("Unexpected end of file from server") ||
                         e.getMessage().contains("Connection refused"))) {

                    final String errorMsg = "Failed to connect to Huobi. SSL Connection was reset by the server.";
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                /*
                 * Exchange sometimes fails with these codes, but recovers by next request...
                 */
                } else if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 502
                        || exchangeConnection.getResponseCode() == 503
                        || exchangeConnection.getResponseCode() == 504)) {

                    final String errorMsg = IO_50X_TIMEOUT_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                } else {
                    final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    throw new TradingApiException(errorMsg, e);
                }
            } catch (IOException e1) {

                final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                LOG.error(errorMsg, e1);
                throw new TradingApiException(errorMsg, e1);
            }
        } finally {
            if (exchangeConnection != null) {
                exchangeConnection.disconnect();
            }
        }
    }

    /**
     * Sorts the request params alphabetically (uses natural ordering) and returns them as a query string.
     *
     * @param params the request params to sort.
     * @return the query string containing the sorted request params.
     */
    private String createAlphabeticallySortedQueryString(Map<String, String> params) {

        final List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys); // Huobi requires natural/alphabetical ordering of params

        final StringBuilder sortedQueryString = new StringBuilder();
        for (final String param : keys) {
            if (sortedQueryString.length() > 0) {
                sortedQueryString.append("&");
            }
            //noinspection deprecation
            sortedQueryString.append(param).append("=").append(params.get(param));
        }
        return sortedQueryString.toString();
    }

    /**
     * Creates an MD5 hash for a given string and returns the hash as an lowercase string.
     *
     * @param stringToHash the string to create the MD5 hash for.
     * @return the MD5 hash as an lowercase string.
     */
    private String createMd5HashAndReturnAsLowerCaseString(String stringToHash) {

        final char[] HEX_DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

        if (stringToHash == null || stringToHash.isEmpty()) {
            return "";
        }

        messageDigest.update(stringToHash.getBytes());
        final byte[] md5HashInBytes = messageDigest.digest();

        final StringBuilder md5HashAsLowerCaseString = new StringBuilder();
        for (final byte md5HashByte : md5HashInBytes) {
            md5HashAsLowerCaseString.append(HEX_DIGITS[(md5HashByte & 0xf0) >> 4]).append("").append(HEX_DIGITS[md5HashByte & 0xf]);
        }
        return md5HashAsLowerCaseString.toString();
    }

    /**
     * Initialises the secure messaging layer
     * Sets up the Message Digest to safeguard the data we send to the exchange.
     * We fail hard n fast if any of this stuff blows.
     */
    private void initSecureMessageLayer() {

        try {
            messageDigest = MessageDigest.getInstance("MD5");
            initializedSecureMessagingLayer = true;
        } catch (NoSuchAlgorithmException e) {
            final String errorMsg = "Failed to setup MessageDigest for secure message layer. Details: " + e.getMessage();
            LOG.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Config methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Loads Exchange Adapter config.
     */
    private void loadConfig() {

        final String configFile = getConfigFileLocation();
        final Properties configEntries = new Properties();
        final InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(configFile);

        if (inputStream == null) {
            final String errorMsg = "Cannot find Huobi config at: " + configFile + " HINT: is it on BX-bot's classpath?";
            LOG.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        try {
            configEntries.load(inputStream);

            /*
             * Grab the public key
             */
            key = configEntries.getProperty(KEY_PROPERTY_NAME);

            // WARNING: careful when you log this
//            if (LOG.isInfoEnabled()) {
//                LOG.info(KEY_PROPERTY_NAME + ": " + key);
//            }

            if (key == null || key.length() == 0) {
                final String errorMsg = KEY_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            /*
             * Grab the private key
             */
            secret = configEntries.getProperty(SECRET_PROPERTY_NAME);

            // WARNING: careful when you log this
//            if (LOG.isInfoEnabled()) {
//                LOG.info(SECRET_PROPERTY_NAME + ": " + secret);
//            }

            if (secret == null || secret.length() == 0) {
                final String errorMsg = SECRET_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            // Grab the buy fee
            final String buyFeeInConfig = configEntries.getProperty(BUY_FEE_PROPERTY_NAME);
            if (buyFeeInConfig == null || buyFeeInConfig.length() == 0) {
                final String errorMsg = BUY_FEE_PROPERTY_NAME + " cannot be null or zero length!"
                        + " HINT: is the value set in the " + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(BUY_FEE_PROPERTY_NAME + ": " + buyFeeInConfig + "%");
            }

            buyFeePercentage = new BigDecimal(buyFeeInConfig).divide(new BigDecimal("100"), 8, BigDecimal.ROUND_HALF_UP);
            if (LOG.isInfoEnabled()) {
                LOG.info("Buy fee % in BigDecimal format: " + buyFeePercentage);
            }

            // Grab the sell fee
            final String sellFeeInConfig = configEntries.getProperty(SELL_FEE_PROPERTY_NAME);
            if (sellFeeInConfig == null || sellFeeInConfig.length() == 0) {
                final String errorMsg = SELL_FEE_PROPERTY_NAME + " cannot be null or zero length!"
                        + " HINT: is the value set in the " + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(SELL_FEE_PROPERTY_NAME + ": " + sellFeeInConfig + "%");
            }

            sellFeePercentage = new BigDecimal(sellFeeInConfig).divide(new BigDecimal("100"), 8, BigDecimal.ROUND_HALF_UP);
            if (LOG.isInfoEnabled()) {
                LOG.info("Sell fee % in BigDecimal format: " + sellFeePercentage);
            }

            /*
             * Grab the connection timeout
             */
            connectionTimeout = Integer.parseInt( // will barf if not a number; we want this to fail fast.
                    configEntries.getProperty(CONNECTION_TIMEOUT_PROPERTY_NAME));
            if (connectionTimeout == 0) {
                final String errorMsg = CONNECTION_TIMEOUT_PROPERTY_NAME + " cannot be 0 value!"
                        + " HINT: is the value set in the " + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(CONNECTION_TIMEOUT_PROPERTY_NAME + ": " + connectionTimeout);
            }

            /*
             * Grab the account info market
             */
            accountInfoMarket = configEntries.getProperty(ACCOUNT_INFO_MARKET_PROPERTY_NAME);
            if (accountInfoMarket == null || accountInfoMarket.length() == 0) {
                final String errorMsg = ACCOUNT_INFO_MARKET_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(ACCOUNT_INFO_MARKET_PROPERTY_NAME + ": " + accountInfoMarket);
            }

        } catch (IOException e) {
            final String errorMsg = "Failed to load Exchange config: " + configFile;
            LOG.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                final String errorMsg = "Failed to close input stream for: " + configFile;
                LOG.error(errorMsg, e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Util methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Initialises the GSON layer.
     */
    private void initGson() {
        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(HuobiOpenOrderResponseWrapper.class, new GetHuobiOpenOrdersDeserializer());
        gson = gsonBuilder.create();
    }

    /**
     * <p>
     * Returns the authenticated request marketId for a given public request marketId. The mapping is done here
     * so we only expect the public request marketId in the {@link TradingApi} methods, e.g. BTC-USD, BTC-CNY.
     * Telling users to use one marketId for 'public' API calls and another marketId for 'authenticated' API calls is a
     * big no-no.
     * </p>
     *
     * <p>
     * This is a real pain... why does the Huobi API require the fiat currency for the authenticated
     * requests' 'market' id param?
     * </p>
     *
     * @param publicRequestMarketId the public request marketId, e.g. BTC-USD
     * @return the authenticated request marketId, e.g. usd
     */
    private String getAuthenticatedMarketIdForGivenPublicMarketId(String publicRequestMarketId) {

        final String authenticatedRequestMarketId;

        if (publicRequestMarketId.equals(PublicExchangeCallMarket.BTC_USD.getStringValue())) {
            authenticatedRequestMarketId = AuthenticatedExchangeCallMarket.USD.getStringValue();

        } else  if (publicRequestMarketId.equals(PublicExchangeCallMarket.BTC_CNY.getStringValue())) {
            authenticatedRequestMarketId = AuthenticatedExchangeCallMarket.CNY.getStringValue();

        } else {
            final String errorMsg = "Unrecognised marketId to create order for: '" +
                    publicRequestMarketId + "'. Supported markets are: " +
                    Arrays.toString(PublicExchangeCallMarket.values());
            LOG.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        return authenticatedRequestMarketId;
    }

    /*
     * Hack for unit-testing config loading.
     */
    private static String getConfigFileLocation() {
        return CONFIG_FILE;
    }

    /*
     * Hack for unit-testing map params passed to transport layer.
     */
    private Map<String, String> getRequestParamMap() {
        return new HashMap<>();
    }
}